import * as anchor from "@coral-xyz/anchor";
import { BN, Program } from "@coral-xyz/anchor";
import {
  Keypair,
  LAMPORTS_PER_SOL,
  PublicKey,
  SystemProgram,
} from "@solana/web3.js";
import {
  ASSOCIATED_TOKEN_PROGRAM_ID,
  TOKEN_PROGRAM_ID,
  createMint,
  getAccount,
  getAssociatedTokenAddressSync,
  getOrCreateAssociatedTokenAccount,
  mintTo,
  transfer,
} from "@solana/spl-token";
import { assert } from "chai";
import { createHash } from "crypto";
import { Taptrade } from "../target/types/taptrade";

const DECIMALS = 6;
const PRICE = 20_000_000; // 20 tUSDC
const START_BALANCE = 1_000_000_000; // 1000 tUSDC

describe("taptrade", () => {
  const provider = anchor.AnchorProvider.env();
  anchor.setProvider(provider);
  const program = anchor.workspace.Taptrade as Program<Taptrade>;
  const conn = provider.connection;
  const payer = (provider.wallet as anchor.Wallet).payer;

  const seller = Keypair.generate();
  const buyer = Keypair.generate();
  const stranger = Keypair.generate();
  let mint: PublicKey;
  let otherMint: PublicKey;
  let nextId = 1;

  const itemHash = Array.from(
    createHash("sha256").update("Calc textbook, 3rd ed.").digest()
  );

  // ---------------------------------------------------------------- helpers

  const pda = (seeds: Buffer[]) =>
    PublicKey.findProgramAddressSync(seeds, program.programId)[0];
  const listingPda = (s: PublicKey, id: number) =>
    pda([
      Buffer.from("listing"),
      s.toBuffer(),
      new BN(id).toArrayLike(Buffer, "le", 8),
    ]);
  const escrowPda = (listing: PublicKey) =>
    pda([Buffer.from("escrow"), listing.toBuffer()]);
  const repPda = (wallet: PublicKey) =>
    pda([Buffer.from("rep"), wallet.toBuffer()]);
  const ata = (owner: PublicKey, m: PublicKey = mint) =>
    getAssociatedTokenAddressSync(m, owner, true);

  const balance = async (addr: PublicKey) =>
    Number((await getAccount(conn, addr)).amount);

  const chainNow = async () => {
    const t = await conn.getBlockTime(await conn.getSlot("confirmed"));
    return t ?? Math.floor(Date.now() / 1000);
  };

  async function airdrop(pk: PublicKey) {
    const sig = await conn.requestAirdrop(pk, 10 * LAMPORTS_PER_SOL);
    const bh = await conn.getLatestBlockhash();
    await conn.confirmTransaction({ signature: sig, ...bh }, "confirmed");
  }

  async function expectErr(p: Promise<unknown>, codes: string | string[]) {
    const want = Array.isArray(codes) ? codes : [codes];
    try {
      await p;
    } catch (e: any) {
      const code: string | undefined = e?.error?.errorCode?.code;
      const text = `${e?.message ?? e}\n${(e?.logs ?? []).join("\n")}`;
      const hit = want.some((c) => code === c || text.includes(c));
      assert.isTrue(hit, `expected one of [${want}], got: ${code ?? text}`);
      return;
    }
    assert.fail(`expected error [${want}] but the tx succeeded`);
  }

  async function createListing(price = PRICE, owner = seller) {
    const id = nextId++;
    const listing = listingPda(owner.publicKey, id);
    await program.methods
      .createListing(new BN(id), new BN(price), mint, itemHash)
      .accountsPartial({
        seller: owner.publicKey,
        listing,
        mintAccount: mint,
        systemProgram: SystemProgram.programId,
      })
      .signers([owner])
      .rpc();
    return listing;
  }

  function fundTx(
    listing: PublicKey,
    deadline: number,
    who: Keypair = buyer,
    m: PublicKey = mint
  ) {
    const escrow = escrowPda(listing);
    return program.methods
      .fundEscrow(new BN(deadline))
      .accountsPartial({
        buyer: who.publicKey,
        listing,
        escrow,
        mint: m,
        buyerToken: ata(who.publicKey, m),
        vault: ata(escrow, m),
        tokenProgram: TOKEN_PROGRAM_ID,
        associatedTokenProgram: ASSOCIATED_TOKEN_PROGRAM_ID,
        systemProgram: SystemProgram.programId,
      })
      .signers([who]);
  }

  async function fund(listing: PublicKey, deadline?: number) {
    await fundTx(listing, deadline ?? (await chainNow()) + 3600).rpc();
    return escrowPda(listing);
  }

  type ReleaseOverrides = Partial<{
    signer: Keypair;
    seller: PublicKey;
    mint: PublicKey;
    vault: PublicKey;
  }>;

  function releaseTx(listing: PublicKey, o: ReleaseOverrides = {}) {
    const escrow = escrowPda(listing);
    const signer = o.signer ?? buyer;
    const s = o.seller ?? seller.publicKey;
    const m = o.mint ?? mint;
    return program.methods
      .release()
      .accountsPartial({
        buyer: signer.publicKey,
        seller: s,
        listing,
        escrow,
        mint: m,
        vault: o.vault ?? ata(escrow, m),
        sellerToken: ata(s, m),
        buyerRep: repPda(signer.publicKey),
        sellerRep: repPda(s),
        tokenProgram: TOKEN_PROGRAM_ID,
        associatedTokenProgram: ASSOCIATED_TOKEN_PROGRAM_ID,
        systemProgram: SystemProgram.programId,
      })
      .signers([signer]);
  }

  function refundTx(listing: PublicKey, caller: Keypair = stranger) {
    const escrow = escrowPda(listing);
    return program.methods
      .refundAfterDeadline()
      .accountsPartial({
        caller: caller.publicKey,
        buyer: buyer.publicKey,
        seller: seller.publicKey,
        listing,
        escrow,
        mint,
        vault: ata(escrow),
        buyerToken: ata(buyer.publicKey),
        sellerRep: repPda(seller.publicKey),
        tokenProgram: TOKEN_PROGRAM_ID,
        associatedTokenProgram: ASSOCIATED_TOKEN_PROGRAM_ID,
        systemProgram: SystemProgram.programId,
      })
      .signers([caller]);
  }

  const rep = async (wallet: PublicKey) => {
    const r = await program.account.reputation.fetchNullable(repPda(wallet));
    return r
      ? { completed: r.completed, refunded: r.refunded, disputed: r.disputed }
      : { completed: 0, refunded: 0, disputed: 0 };
  };

  // ------------------------------------------------------------------ setup

  before(async () => {
    await Promise.all([seller, buyer, stranger].map((k) => airdrop(k.publicKey)));
    mint = await createMint(conn, payer, payer.publicKey, null, DECIMALS);
    otherMint = await createMint(conn, payer, payer.publicKey, null, DECIMALS);
    for (const m of [mint, otherMint]) {
      for (const k of [seller, buyer, stranger]) {
        const acc = await getOrCreateAssociatedTokenAccount(conn, payer, m, k.publicKey);
        await mintTo(conn, payer, m, acc.address, payer, START_BALANCE);
      }
    }
  });

  // ---------------------------------------------------------- create_listing

  describe("create_listing", () => {
    it("creates an Open listing", async () => {
      const listing = await createListing();
      const l = await program.account.listing.fetch(listing);
      assert.ok(l.seller.equals(seller.publicKey));
      assert.ok(l.mint.equals(mint));
      assert.equal(l.price.toNumber(), PRICE);
      assert.deepEqual(l.itemHash, itemHash);
      assert.deepEqual(l.status, { open: {} });
    });

    it("rejects price 0", async () => {
      await expectErr(createListing(0), "InvalidPrice");
    });

    it("rejects a mint arg that doesn't match the mint account", async () => {
      const id = nextId++;
      await expectErr(
        program.methods
          .createListing(new BN(id), new BN(PRICE), otherMint, itemHash)
          .accountsPartial({
            seller: seller.publicKey,
            listing: listingPda(seller.publicKey, id),
            mintAccount: mint,
            systemProgram: SystemProgram.programId,
          })
          .signers([seller])
          .rpc(),
        "WrongMint"
      );
    });
  });

  // ---------------------------------------------------------- cancel_listing

  describe("cancel_listing", () => {
    it("only the seller can cancel", async () => {
      const listing = await createListing();
      await expectErr(
        program.methods
          .cancelListing()
          .accountsPartial({ seller: stranger.publicKey, listing })
          .signers([stranger])
          .rpc(),
        "WrongSigner"
      );
    });

    it("cancels Open → Cancelled, then can't cancel or fund again", async () => {
      const listing = await createListing();
      await program.methods
        .cancelListing()
        .accountsPartial({ seller: seller.publicKey, listing })
        .signers([seller])
        .rpc();
      const l = await program.account.listing.fetch(listing);
      assert.deepEqual(l.status, { cancelled: {} });

      await expectErr(
        program.methods
          .cancelListing()
          .accountsPartial({ seller: seller.publicKey, listing })
          .signers([seller])
          .rpc(),
        "ListingNotOpen"
      );
      await expectErr(fund(listing), "ListingNotOpen");
    });

    it("can't cancel a Funded listing", async () => {
      const listing = await createListing();
      await fund(listing);
      await expectErr(
        program.methods
          .cancelListing()
          .accountsPartial({ seller: seller.publicKey, listing })
          .signers([seller])
          .rpc(),
        "ListingNotOpen"
      );
    });
  });

  // ------------------------------------------------------------- fund_escrow

  describe("fund_escrow", () => {
    it("moves exactly `price` into the vault", async () => {
      const listing = await createListing();
      const before = await balance(ata(buyer.publicKey));
      const deadline = (await chainNow()) + 3600;
      const escrow = await fund(listing, deadline);

      assert.equal(await balance(ata(escrow)), PRICE);
      assert.equal(await balance(ata(buyer.publicKey)), before - PRICE);

      const e = await program.account.escrow.fetch(escrow);
      assert.ok(e.buyer.equals(buyer.publicKey));
      assert.ok(e.seller.equals(seller.publicKey));
      assert.ok(e.listing.equals(listing));
      assert.ok(e.mint.equals(mint));
      assert.ok(e.vault.equals(ata(escrow)));
      assert.equal(e.amount.toNumber(), PRICE);
      assert.equal(e.deadline.toNumber(), deadline);
      assert.deepEqual(e.status, { funded: {} });

      const l = await program.account.listing.fetch(listing);
      assert.deepEqual(l.status, { funded: {} });
    });

    it("rejects the wrong mint", async () => {
      const listing = await createListing();
      const deadline = (await chainNow()) + 3600;
      await expectErr(fundTx(listing, deadline, buyer, otherMint).rpc(), "WrongMint");
    });

    it("rejects a seller buying their own listing", async () => {
      const listing = await createListing();
      const deadline = (await chainNow()) + 3600;
      await expectErr(fundTx(listing, deadline, seller).rpc(), "SelfTrade");
    });

    it("rejects a deadline in the past", async () => {
      const listing = await createListing();
      await expectErr(fund(listing, (await chainNow()) - 10), "DeadlineInPast");
    });

    it("rejects a deadline more than 30 days out", async () => {
      const listing = await createListing();
      await expectErr(
        fund(listing, (await chainNow()) + 31 * 24 * 3600),
        "DeadlineTooFar"
      );
    });

    it("can't fund twice", async () => {
      const listing = await createListing();
      await fund(listing);
      await expectErr(fund(listing), ["already in use", "ListingNotOpen"]);
    });
  });

  // ----------------------------------------------------------------- release

  describe("release", () => {
    let listing: PublicKey;
    let escrow: PublicKey;

    before(async () => {
      listing = await createListing();
      escrow = await fund(listing);
    });

    it("rejects a non-buyer signer", async () => {
      await expectErr(releaseTx(listing, { signer: stranger }).rpc(), "WrongSigner");
    });

    it("rejects the wrong seller (redirected payout)", async () => {
      await expectErr(
        releaseTx(listing, { seller: stranger.publicKey }).rpc(),
        "WrongSeller"
      );
    });

    it("rejects the wrong vault", async () => {
      await expectErr(
        releaseTx(listing, { vault: ata(buyer.publicKey) }).rpc(),
        "WrongVault"
      );
    });

    it("rejects the wrong mint", async () => {
      // Keep the real vault so the failure comes from the mint check rather than
      // a missing account.
      await expectErr(
        releaseTx(listing, { mint: otherMint, vault: ata(escrow) }).rpc(),
        "WrongMint"
      );
    });

    it("refund before the deadline fails", async () => {
      await expectErr(refundTx(listing).rpc(), "DeadlineNotReached");
    });

    it("pays the seller exactly `amount`, closes the vault, and bumps both reputations", async () => {
      const sellerBefore = await balance(ata(seller.publicKey));
      const repBuyerBefore = await rep(buyer.publicKey);
      const repSellerBefore = await rep(seller.publicKey);

      await releaseTx(listing).rpc();

      assert.equal(await balance(ata(seller.publicKey)), sellerBefore + PRICE);
      assert.isNull(await conn.getAccountInfo(ata(escrow)), "vault closed");

      const e = await program.account.escrow.fetch(escrow);
      assert.deepEqual(e.status, { released: {} });
      const l = await program.account.listing.fetch(listing);
      assert.deepEqual(l.status, { closed: {} });

      const rb = await rep(buyer.publicKey);
      const rs = await rep(seller.publicKey);
      assert.equal(rb.completed, repBuyerBefore.completed + 1);
      assert.equal(rs.completed, repSellerBefore.completed + 1);
      assert.equal(rs.refunded, repSellerBefore.refunded);

      const raw = await program.account.reputation.fetch(repPda(seller.publicKey));
      assert.isAbove(raw.firstTradeTs.toNumber(), 0);
    });

    it("can't release twice", async () => {
      const sellerBefore = await balance(ata(seller.publicKey));
      await expectErr(releaseTx(listing).rpc(), [
        "AlreadySettled",
        "AccountNotInitialized",
      ]);
      assert.equal(await balance(ata(seller.publicKey)), sellerBefore);
    });

    it("can't refund after release", async () => {
      await expectErr(refundTx(listing).rpc(), [
        "AlreadySettled",
        "AccountNotInitialized",
      ]);
    });

    it("a token donation to the vault can't block release, and still no double release", async () => {
      const l2 = await createListing();
      const e2 = await fund(l2);
      // Griefer sends 1 base unit to the vault.
      await transfer(conn, stranger, ata(stranger.publicKey), ata(e2), stranger, 1);

      const sellerBefore = await balance(ata(seller.publicKey));
      await releaseTx(l2).rpc();
      assert.equal(await balance(ata(seller.publicKey)), sellerBefore + PRICE);
      assert.equal(await balance(ata(e2)), 1, "vault left open with the donation");

      await expectErr(releaseTx(l2).rpc(), "AlreadySettled");
      assert.equal(await balance(ata(seller.publicKey)), sellerBefore + PRICE);
    });
  });

  // --------------------------------------------------- refund_after_deadline

  describe("refund_after_deadline", () => {
    it("after the deadline anyone can refund the buyer; seller.refunded += 1", async () => {
      const listing = await createListing();
      const escrow = await fund(listing, (await chainNow()) + 3);

      await expectErr(refundTx(listing).rpc(), "DeadlineNotReached");

      const buyerBefore = await balance(ata(buyer.publicKey));
      const repSellerBefore = await rep(seller.publicKey);
      const repBuyerBefore = await rep(buyer.publicKey);

      // Wait for the validator clock to pass the deadline.
      let done = false;
      for (let i = 0; i < 30 && !done; i++) {
        await new Promise((r) => setTimeout(r, 1000));
        try {
          await refundTx(listing).rpc();
          done = true;
        } catch (e: any) {
          if (e?.error?.errorCode?.code !== "DeadlineNotReached") throw e;
        }
      }
      assert.isTrue(done, "refund never became possible");

      assert.equal(await balance(ata(buyer.publicKey)), buyerBefore + PRICE);
      assert.isNull(await conn.getAccountInfo(ata(escrow)), "vault closed");

      const e = await program.account.escrow.fetch(escrow);
      assert.deepEqual(e.status, { refunded: {} });
      const l = await program.account.listing.fetch(listing);
      assert.deepEqual(l.status, { closed: {} });

      const rs = await rep(seller.publicKey);
      assert.equal(rs.refunded, repSellerBefore.refunded + 1);
      assert.equal(rs.completed, repSellerBefore.completed);
      assert.deepEqual(await rep(buyer.publicKey), repBuyerBefore);

      // No double refund, no release after refund.
      await expectErr(refundTx(listing).rpc(), [
        "AlreadySettled",
        "AccountNotInitialized",
      ]);
      await expectErr(releaseTx(listing).rpc(), [
        "AlreadySettled",
        "AccountNotInitialized",
      ]);
      assert.equal(await balance(ata(buyer.publicKey)), buyerBefore + PRICE);
    });
  });

  // -------------------------------------------------------------- reputation

  describe("reputation write paths", () => {
    it("only release and refund_after_deadline take a reputation account", () => {
      const writers = program.idl.instructions
        .filter((ix) => ix.accounts.some((a) => a.name.toLowerCase().endsWith("rep")))
        .map((ix) => ix.name.replace(/_/g, "").toLowerCase())
        .sort();
      assert.deepEqual(writers, ["refundafterdeadline", "release"]);
    });

    it("create, cancel and fund leave reputation unchanged", async () => {
      const before = [await rep(seller.publicKey), await rep(buyer.publicKey)];
      const a = await createListing();
      await program.methods
        .cancelListing()
        .accountsPartial({ seller: seller.publicKey, listing: a })
        .signers([seller])
        .rpc();
      const b = await createListing();
      await fund(b);
      const after = [await rep(seller.publicKey), await rep(buyer.publicKey)];
      assert.deepEqual(after, before);
    });

    it("a fresh wallet has no reputation account until it trades", async () => {
      assert.isNull(await conn.getAccountInfo(repPda(stranger.publicKey)));
    });
  });
});
