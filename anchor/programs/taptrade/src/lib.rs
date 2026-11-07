use anchor_lang::prelude::*;
use anchor_spl::{
    associated_token::AssociatedToken,
    token::{self, CloseAccount, Mint, Token, TokenAccount, TransferChecked},
};

// Program ID from target/deploy/taptrade-keypair.json (`anchor keys sync`).
declare_id!("GpbVJKqd8upWam5fskxxkfkkz28aFAxpbbLuNDzoGnR2");

/// Max escrow deadline, measured from the time of funding.
pub const MAX_DEADLINE_SECS: i64 = 30 * 24 * 60 * 60;
/// Max listing title length in bytes (UTF-8).
pub const MAX_TITLE_LEN: usize = 64;

pub const LISTING_SEED: &[u8] = b"listing";
pub const ESCROW_SEED: &[u8] = b"escrow";
pub const REP_SEED: &[u8] = b"rep";

/// Design notes (why this lives on Solana, not in a database):
/// - Listings, including their title, are on-chain: any client can rebuild the
///   whole marketplace from `getProgramAccounts` + events. The backend is only
///   a convenience indexer / tx builder.
/// - Every settled trade closes its accounts and refunds their rent, so a
///   trade's net cost is just transaction fees (fractions of a cent).
/// - Each state change emits an event, so trade history stays readable after
///   the accounts are closed and other programs/indexers can follow along.
/// - `Reputation` is written only by this program and is a plain PDA any other
///   program can read.
#[program]
pub mod taptrade {
    use super::*;

    pub fn create_listing(
        ctx: Context<CreateListing>,
        listing_id: u64,
        price: u64,
        mint: Pubkey,
        title: String,
        item_hash: [u8; 32],
    ) -> Result<()> {
        require!(price > 0, TapError::InvalidPrice);
        require!(
            !title.trim().is_empty() && title.len() <= MAX_TITLE_LEN,
            TapError::InvalidTitle
        );

        let listing = &mut ctx.accounts.listing;
        listing.seller = ctx.accounts.seller.key();
        listing.listing_id = listing_id;
        listing.price = price;
        listing.mint = mint;
        listing.title = title.clone();
        listing.item_hash = item_hash;
        listing.status = ListingStatus::Open;
        listing.bump = ctx.bumps.listing;

        emit!(ListingCreated {
            listing: listing.key(),
            seller: listing.seller,
            listing_id,
            price,
            mint,
            title,
            item_hash,
        });
        Ok(())
    }

    /// Open → account closed; rent back to the seller.
    pub fn cancel_listing(ctx: Context<CancelListing>) -> Result<()> {
        let listing = &ctx.accounts.listing;
        require!(listing.status == ListingStatus::Open, TapError::ListingNotOpen);
        emit!(ListingCancelled {
            listing: listing.key(),
            seller: listing.seller,
        });
        Ok(())
    }

    pub fn fund_escrow(ctx: Context<FundEscrow>, deadline: i64) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let listing = &ctx.accounts.listing;

        require!(listing.status == ListingStatus::Open, TapError::ListingNotOpen);
        require_keys_neq!(ctx.accounts.buyer.key(), listing.seller, TapError::SelfTrade);
        require!(deadline > now, TapError::DeadlineInPast);
        let max = now
            .checked_add(MAX_DEADLINE_SECS)
            .ok_or(TapError::MathOverflow)?;
        require!(deadline <= max, TapError::DeadlineTooFar);

        let amount = listing.price;
        let seller = listing.seller;

        token::transfer_checked(
            CpiContext::new(
                ctx.accounts.token_program.to_account_info(),
                TransferChecked {
                    from: ctx.accounts.buyer_token.to_account_info(),
                    mint: ctx.accounts.mint.to_account_info(),
                    to: ctx.accounts.vault.to_account_info(),
                    authority: ctx.accounts.buyer.to_account_info(),
                },
            ),
            amount,
            ctx.accounts.mint.decimals,
        )?;

        let escrow = &mut ctx.accounts.escrow;
        escrow.buyer = ctx.accounts.buyer.key();
        escrow.seller = seller;
        escrow.listing = ctx.accounts.listing.key();
        escrow.mint = ctx.accounts.mint.key();
        escrow.vault = ctx.accounts.vault.key();
        escrow.amount = amount;
        escrow.deadline = deadline;
        escrow.bump = ctx.bumps.escrow;

        ctx.accounts.listing.status = ListingStatus::Funded;

        emit!(EscrowFunded {
            listing: escrow.listing,
            escrow: escrow.key(),
            buyer: escrow.buyer,
            seller,
            mint: escrow.mint,
            amount,
            deadline,
        });
        Ok(())
    }

    /// Buyer releases the vault to the seller. Listing and escrow are closed
    /// (rent → seller / buyer); both reputations `completed += 1`.
    pub fn release(ctx: Context<Release>) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;

        let listing_key = ctx.accounts.listing.key();
        let bump = [ctx.accounts.escrow.bump];
        let seeds: &[&[u8]] = &[ESCROW_SEED, listing_key.as_ref(), &bump];
        let amount = ctx.accounts.escrow.amount;

        pay_out_of_vault(
            &ctx.accounts.token_program,
            &mut ctx.accounts.vault,
            &ctx.accounts.mint,
            ctx.accounts.seller_token.to_account_info(),
            ctx.accounts.escrow.to_account_info(),
            ctx.accounts.buyer.to_account_info(),
            amount,
            &[seeds],
        )?;

        let buyer_rep = &mut ctx.accounts.buyer_rep;
        touch_rep(buyer_rep, ctx.bumps.buyer_rep, now);
        buyer_rep.completed = buyer_rep
            .completed
            .checked_add(1)
            .ok_or(TapError::MathOverflow)?;

        let seller_rep = &mut ctx.accounts.seller_rep;
        touch_rep(seller_rep, ctx.bumps.seller_rep, now);
        seller_rep.completed = seller_rep
            .completed
            .checked_add(1)
            .ok_or(TapError::MathOverflow)?;

        emit!(EscrowReleased {
            listing: listing_key,
            escrow: ctx.accounts.escrow.key(),
            buyer: ctx.accounts.buyer.key(),
            seller: ctx.accounts.seller.key(),
            mint: ctx.accounts.mint.key(),
            amount,
        });
        Ok(())
    }

    /// After the deadline anyone can refund the buyer. The escrow is closed
    /// (rent → buyer) and the listing goes back to Open so the seller can sell
    /// to someone else. Seller `refunded += 1`.
    pub fn refund_after_deadline(ctx: Context<RefundAfterDeadline>) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        require!(
            now > ctx.accounts.escrow.deadline,
            TapError::DeadlineNotReached
        );

        let listing_key = ctx.accounts.listing.key();
        let bump = [ctx.accounts.escrow.bump];
        let seeds: &[&[u8]] = &[ESCROW_SEED, listing_key.as_ref(), &bump];
        let amount = ctx.accounts.escrow.amount;

        pay_out_of_vault(
            &ctx.accounts.token_program,
            &mut ctx.accounts.vault,
            &ctx.accounts.mint,
            ctx.accounts.buyer_token.to_account_info(),
            ctx.accounts.escrow.to_account_info(),
            ctx.accounts.buyer.to_account_info(),
            amount,
            &[seeds],
        )?;

        ctx.accounts.listing.status = ListingStatus::Open;

        let seller_rep = &mut ctx.accounts.seller_rep;
        touch_rep(seller_rep, ctx.bumps.seller_rep, now);
        seller_rep.refunded = seller_rep
            .refunded
            .checked_add(1)
            .ok_or(TapError::MathOverflow)?;

        emit!(EscrowRefunded {
            listing: listing_key,
            escrow: ctx.accounts.escrow.key(),
            buyer: ctx.accounts.buyer.key(),
            seller: ctx.accounts.seller.key(),
            mint: ctx.accounts.mint.key(),
            amount,
        });
        Ok(())
    }
}

/// Moves exactly `amount` out of the vault, signed by the escrow PDA, then
/// closes the vault (rent to the buyer) if it is empty. If someone sent extra
/// tokens to the vault, it stays open so the payout still succeeds.
#[allow(clippy::too_many_arguments)]
fn pay_out_of_vault<'info>(
    token_program: &Program<'info, Token>,
    vault: &mut Box<Account<'info, TokenAccount>>,
    mint: &Account<'info, Mint>,
    to: AccountInfo<'info>,
    escrow: AccountInfo<'info>,
    rent_to: AccountInfo<'info>,
    amount: u64,
    signer: &[&[&[u8]]],
) -> Result<()> {
    token::transfer_checked(
        CpiContext::new_with_signer(
            token_program.to_account_info(),
            TransferChecked {
                from: vault.to_account_info(),
                mint: mint.to_account_info(),
                to,
                authority: escrow.clone(),
            },
            signer,
        ),
        amount,
        mint.decimals,
    )?;

    vault.reload()?;
    if vault.amount == 0 {
        token::close_account(CpiContext::new_with_signer(
            token_program.to_account_info(),
            CloseAccount {
                account: vault.to_account_info(),
                destination: rent_to,
                authority: escrow,
            },
            signer,
        ))?;
    }
    Ok(())
}

fn touch_rep(rep: &mut Reputation, bump: u8, now: i64) {
    if rep.first_trade_ts == 0 {
        rep.first_trade_ts = now;
        rep.bump = bump;
    }
}

// ---------------------------------------------------------------------------
// Accounts
// ---------------------------------------------------------------------------

#[derive(Accounts)]
#[instruction(listing_id: u64, price: u64, mint: Pubkey)]
pub struct CreateListing<'info> {
    #[account(mut)]
    pub seller: Signer<'info>,

    #[account(
        init,
        payer = seller,
        space = 8 + Listing::INIT_SPACE,
        seeds = [LISTING_SEED, seller.key().as_ref(), &listing_id.to_le_bytes()],
        bump,
    )]
    pub listing: Account<'info, Listing>,

    /// Proves `mint` is a real SPL mint.
    #[account(address = mint @ TapError::WrongMint)]
    pub mint_account: Account<'info, Mint>,

    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct CancelListing<'info> {
    #[account(mut)]
    pub seller: Signer<'info>,

    #[account(
        mut,
        seeds = [LISTING_SEED, listing.seller.as_ref(), &listing.listing_id.to_le_bytes()],
        bump = listing.bump,
        has_one = seller @ TapError::WrongSigner,
        close = seller,
    )]
    pub listing: Account<'info, Listing>,
}

#[derive(Accounts)]
pub struct FundEscrow<'info> {
    #[account(mut)]
    pub buyer: Signer<'info>,

    #[account(
        mut,
        seeds = [LISTING_SEED, listing.seller.as_ref(), &listing.listing_id.to_le_bytes()],
        bump = listing.bump,
        has_one = mint @ TapError::WrongMint,
    )]
    pub listing: Box<Account<'info, Listing>>,

    #[account(
        init,
        payer = buyer,
        space = 8 + Escrow::INIT_SPACE,
        seeds = [ESCROW_SEED, listing.key().as_ref()],
        bump,
    )]
    pub escrow: Box<Account<'info, Escrow>>,

    pub mint: Box<Account<'info, Mint>>,

    #[account(
        mut,
        associated_token::mint = mint,
        associated_token::authority = buyer,
    )]
    pub buyer_token: Box<Account<'info, TokenAccount>>,

    #[account(
        init,
        payer = buyer,
        associated_token::mint = mint,
        associated_token::authority = escrow,
    )]
    pub vault: Box<Account<'info, TokenAccount>>,

    pub token_program: Program<'info, Token>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct Release<'info> {
    /// Receives the escrow + vault rent back.
    #[account(mut)]
    pub buyer: Signer<'info>,

    /// CHECK: must equal `escrow.seller` and `listing.seller` (has_one below).
    /// Receives the listing rent back.
    #[account(mut)]
    pub seller: UncheckedAccount<'info>,

    #[account(
        mut,
        seeds = [LISTING_SEED, listing.seller.as_ref(), &listing.listing_id.to_le_bytes()],
        bump = listing.bump,
        has_one = mint @ TapError::WrongMint,
        has_one = seller @ TapError::WrongSeller,
        close = seller,
    )]
    pub listing: Box<Account<'info, Listing>>,

    #[account(
        mut,
        seeds = [ESCROW_SEED, listing.key().as_ref()],
        bump = escrow.bump,
        has_one = buyer @ TapError::WrongSigner,
        has_one = seller @ TapError::WrongSeller,
        has_one = listing @ TapError::WrongListing,
        has_one = mint @ TapError::WrongMint,
        has_one = vault @ TapError::WrongVault,
        close = buyer,
    )]
    pub escrow: Box<Account<'info, Escrow>>,

    pub mint: Box<Account<'info, Mint>>,

    #[account(mut)]
    pub vault: Box<Account<'info, TokenAccount>>,

    #[account(
        init_if_needed,
        payer = buyer,
        associated_token::mint = mint,
        associated_token::authority = seller,
    )]
    pub seller_token: Box<Account<'info, TokenAccount>>,

    #[account(
        init_if_needed,
        payer = buyer,
        space = 8 + Reputation::INIT_SPACE,
        seeds = [REP_SEED, buyer.key().as_ref()],
        bump,
    )]
    pub buyer_rep: Box<Account<'info, Reputation>>,

    #[account(
        init_if_needed,
        payer = buyer,
        space = 8 + Reputation::INIT_SPACE,
        seeds = [REP_SEED, seller.key().as_ref()],
        bump,
    )]
    pub seller_rep: Box<Account<'info, Reputation>>,

    pub token_program: Program<'info, Token>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct RefundAfterDeadline<'info> {
    /// Anyone may trigger a refund after the deadline; they pay any rent needed.
    #[account(mut)]
    pub caller: Signer<'info>,

    /// CHECK: must equal `escrow.buyer` (has_one below). Receives the escrow + vault rent.
    #[account(mut)]
    pub buyer: UncheckedAccount<'info>,

    /// CHECK: must equal `escrow.seller` (has_one below).
    pub seller: UncheckedAccount<'info>,

    #[account(
        mut,
        seeds = [LISTING_SEED, listing.seller.as_ref(), &listing.listing_id.to_le_bytes()],
        bump = listing.bump,
        has_one = mint @ TapError::WrongMint,
        has_one = seller @ TapError::WrongSeller,
    )]
    pub listing: Box<Account<'info, Listing>>,

    #[account(
        mut,
        seeds = [ESCROW_SEED, listing.key().as_ref()],
        bump = escrow.bump,
        has_one = buyer @ TapError::WrongBuyer,
        has_one = seller @ TapError::WrongSeller,
        has_one = listing @ TapError::WrongListing,
        has_one = mint @ TapError::WrongMint,
        has_one = vault @ TapError::WrongVault,
        close = buyer,
    )]
    pub escrow: Box<Account<'info, Escrow>>,

    pub mint: Box<Account<'info, Mint>>,

    #[account(mut)]
    pub vault: Box<Account<'info, TokenAccount>>,

    #[account(
        init_if_needed,
        payer = caller,
        associated_token::mint = mint,
        associated_token::authority = buyer,
    )]
    pub buyer_token: Box<Account<'info, TokenAccount>>,

    #[account(
        init_if_needed,
        payer = caller,
        space = 8 + Reputation::INIT_SPACE,
        seeds = [REP_SEED, seller.key().as_ref()],
        bump,
    )]
    pub seller_rep: Box<Account<'info, Reputation>>,

    pub token_program: Program<'info, Token>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

/// Exists while Open or Funded. Closed on `release` / `cancel_listing`.
#[account]
#[derive(InitSpace)]
pub struct Listing {
    pub seller: Pubkey,
    pub listing_id: u64,
    /// Token base units.
    pub price: u64,
    pub mint: Pubkey,
    /// Human-readable title, on-chain so any client can render the listing.
    #[max_len(MAX_TITLE_LEN)]
    pub title: String,
    /// Hash of the full off-chain description / photo, for integrity.
    pub item_hash: [u8; 32],
    pub status: ListingStatus,
    pub bump: u8,
}

/// Exists only while funds are locked. Closed on `release` / `refund_after_deadline`,
/// which is also what makes double release / double refund impossible.
#[account]
#[derive(InitSpace)]
pub struct Escrow {
    pub buyer: Pubkey,
    pub seller: Pubkey,
    pub listing: Pubkey,
    pub mint: Pubkey,
    /// ATA of (escrow PDA, mint).
    pub vault: Pubkey,
    pub amount: u64,
    pub deadline: i64,
    pub bump: u8,
}

/// Written only by `release` and `refund_after_deadline`. Never closed.
/// Readable by any program: PDA `["rep", wallet]` owned by this program.
#[account]
#[derive(InitSpace)]
pub struct Reputation {
    pub completed: u32,
    pub refunded: u32,
    pub disputed: u32,
    pub first_trade_ts: i64,
    pub bump: u8,
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, InitSpace)]
pub enum ListingStatus {
    Open,
    Funded,
}

// ---------------------------------------------------------------------------
// Events (trade history survives account closing; indexers subscribe to these)
// ---------------------------------------------------------------------------

#[event]
pub struct ListingCreated {
    pub listing: Pubkey,
    pub seller: Pubkey,
    pub listing_id: u64,
    pub price: u64,
    pub mint: Pubkey,
    pub title: String,
    pub item_hash: [u8; 32],
}

#[event]
pub struct ListingCancelled {
    pub listing: Pubkey,
    pub seller: Pubkey,
}

#[event]
pub struct EscrowFunded {
    pub listing: Pubkey,
    pub escrow: Pubkey,
    pub buyer: Pubkey,
    pub seller: Pubkey,
    pub mint: Pubkey,
    pub amount: u64,
    pub deadline: i64,
}

#[event]
pub struct EscrowReleased {
    pub listing: Pubkey,
    pub escrow: Pubkey,
    pub buyer: Pubkey,
    pub seller: Pubkey,
    pub mint: Pubkey,
    pub amount: u64,
}

#[event]
pub struct EscrowRefunded {
    pub listing: Pubkey,
    pub escrow: Pubkey,
    pub buyer: Pubkey,
    pub seller: Pubkey,
    pub mint: Pubkey,
    pub amount: u64,
}

#[error_code]
pub enum TapError {
    #[msg("Price must be greater than zero")]
    InvalidPrice,
    #[msg("Title must be 1-64 bytes")]
    InvalidTitle,
    #[msg("Mint does not match the listing")]
    WrongMint,
    #[msg("Signer is not authorized for this action")]
    WrongSigner,
    #[msg("Buyer does not match the escrow")]
    WrongBuyer,
    #[msg("Seller does not match the escrow")]
    WrongSeller,
    #[msg("Listing does not match the escrow")]
    WrongListing,
    #[msg("Vault does not match the escrow")]
    WrongVault,
    #[msg("Listing is not open")]
    ListingNotOpen,
    #[msg("Seller cannot buy their own listing")]
    SelfTrade,
    #[msg("Deadline must be in the future")]
    DeadlineInPast,
    #[msg("Deadline is more than 30 days away")]
    DeadlineTooFar,
    #[msg("Deadline has not passed yet")]
    DeadlineNotReached,
    #[msg("Arithmetic overflow")]
    MathOverflow,
}
