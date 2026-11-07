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
const TITLE = "Calc textbook, 3rd ed.";
const CLOSED = ["AccountNotInitialized", "could not find account", "Account does not exist"];

describe("taptrade", () => {
  // Wait for "confirmed" so reads (balances, closed accounts, tx logs) see each tx.
  const env = anchor.AnchorProvider.env();
  const provider = new anchor.AnchorProvider(env.connection, env.wallet, {
    commitment: "confirmed",
    preflightCommitment: "confirmed",
  });
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

  const itemHash = Array.from(createHash("sha256").update(TITLE).digest());

  // ---------------------------------------------------------------- helpers

  const pda = (seeds: Buffer[]) =>
    PublicKey.findProgramAddressSync(seeds, program.programId)[0];
  const listingPda = (s: PublicKey, id: number) =>
    pda([Buffer.from("listing"), s.toBuffer(), new BN(id).toArrayLike(Buffer, "le", 8)]);
  const escrowPda = (listing: PublicKey) =>
    pda([Buffer.from("escrow"), listing.toBuffer()]);
  const repPda = (wallet: PublicKey) => pda([Buffer.from("rep"), wallet.toBuffer()]);
  const ata = (owner: PublicKey, m: PublicKey = mint) =>
    getAssociatedTokenAddressSync(m, owner, true);

  const balance = async (addr: PublicKey) => Number((await getAccount(conn, addr)).amount);
  const lamports = (pk: PublicKey) => conn.getBalance(pk, "confirmed");
  const exists = async (pk: PublicKey) => (await conn.getAccountInfo(pk, "confirmed")) !== null;

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

  async function eventsOf(sig: string) {
    const tx = await conn.getTransaction(sig, {
      commitment: "confirmed",
      maxSupportedTransactionVersion: 0,
    });
    const parser = new anchor.EventParser(program.programId, program.coder);
    return Array.from(parser.parseLogs(tx?.meta?.logMessages ?? []));
  }

  function createListingTx(opts: { price?: number; owner?: Keypair; title?: string; id?: number } = {}) {
    const owner = opts.owner ?? seller;
    const id = opts.id ?? nextId++;
    const listing = listingPda(owner.publicKey, id);
    const builder = program.methods
      .createListing(new BN(id), new BN(opts.price ?? PRICE), mint, opts.title ?? TITLE, itemHash)
      .accountsPartial({
        seller: owner.publicKey,
        listing,
        mintAccount: mint,
        systemProgram: SystemProgram.programId,
      })
      .signers([owner]);
    return { listing, builder };
  }

  async function createListing(price = PRICE, owner = seller) {
    const { listing, builder } = createListingTx({ price, owner });
    await builder.rpc();
    return listing;
  }

  const cancelTx = (listing: PublicKey, who: Keypair = seller) =>
    program.methods
      .cancelListing()
      .accountsPartial({ seller: who.publicKey, listing })
      .signers([who]);

  function fundTx(listing: PublicKey, deadline: number, who: Keypair = buyer, m: PublicKey = mint) {
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
    it("creates an Open listing with its title on-chain and emits ListingCreated", async () => {
      const { listing, builder } = createListingTx();
      const sig = await builder.rpc();
      const l = await program.account.listing.fetch(listing);
      assert.ok(l.seller.equals(seller.publicKey));
      assert.ok(l.mint.equals(mint));
      assert.equal(l.price.toNumber(), PRICE);
      assert.equal(l.title, TITLE);
      assert.deepEqual(l.itemHash, itemHash);
      assert.deepEqual(l.status, { open: {} });

      const ev = (await eventsOf(sig)).find((e) => e.name === "listingCreated");
      assert.ok(ev, "ListingCreated emitted");
      assert.equal(ev!.data.title, TITLE);
      assert.ok((ev!.data.listing as PublicKey).equals(listing));
    });

    it("rejects price 0", async () => {
      await expectErr(createListing(0), "InvalidPrice");
    });

    it("rejects an empty or >64-byte title", async () => {
      await expectErr(createListingTx({ title: "   " }).builder.rpc(), "InvalidTitle");
      await expectErr(createListingTx({ title: "x".repeat(65) }).builder.rpc(), "InvalidTitle");
      // 64 bytes is fine
      await createListingTx({ title: "y".repeat(64) }).builder.rpc();
    });

    it("rejects a mint arg that doesn't match the mint account", async () => {
      const id = nextId++;
      await expectErr(
        program.methods
          .createListing(new BN(id), new BN(PRICE), otherMint, TITLE, itemHash)
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
      await expectErr(cancelTx(listing, stranger).rpc(), "WrongSigner");
    });

    it("cancel closes the listing and refunds its rent; it can't be cancelled or funded again", async () => {
      const listing = await createListing();
      const rent = (await conn.getAccountInfo(listing))!.lamports;
      const before = await lamports(seller.publicKey);

      const sig = await cancelTx(listing).rpc();

      assert.isFalse(await exists(listing), "listing closed");
      // Rent goes back to the seller (the test provider pays the tx fee).
      assert.equal(await lamports(seller.publicKey), before + rent);
      assert.ok((await eventsOf(sig)).some((e) => e.name === "listingCancelled"));

      await expectErr(cancelTx(listing).rpc(), CLOSED);
      await expectErr(fund(listing), CLOSED);
    });

    it("can't cancel a Funded listing", async () => {
      const listing = await createListing();
      await fund(listing);
      await expectErr(cancelTx(listing).rpc(), "ListingNotOpen");
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
      await expectErr(fund(listing, (await chainNow()) + 31 * 24 * 3600), "DeadlineTooFar");
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
      await expectErr(releaseTx(listing, { seller: stranger.publicKey }).rpc(), "WrongSeller");
    });

    it("rejects the wrong vault", async () => {
      await expectErr(releaseTx(listing, { vault: ata(buyer.publicKey) }).rpc(), "WrongVault");
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

    it("pays the seller exactly `amount`, closes listing/escrow/vault, bumps both reputations, emits EscrowReleased", async () => {
      const sellerBefore = await balance(ata(seller.publicKey));
      const repBuyerBefore = await rep(buyer.publicKey);
      const repSellerBefore = await rep(seller.publicKey);

      const sig = await releaseTx(listing).rpc();

      assert.equal(await balance(ata(seller.publicKey)), sellerBefore + PRICE);
      assert.isFalse(await exists(ata(escrow)), "vault closed");
      assert.isFalse(await exists(escrow), "escrow closed");
      assert.isFalse(await exists(listing), "listing closed");

      const rb = await rep(buyer.publicKey);
      const rs = await rep(seller.publicKey);
      assert.equal(rb.completed, repBuyerBefore.completed + 1);
      assert.equal(rs.completed, repSellerBefore.completed + 1);
      assert.equal(rs.refunded, repSellerBefore.refunded);

      const raw = await program.account.reputation.fetch(repPda(seller.publicKey));
      assert.isAbove(raw.firstTradeTs.toNumber(), 0);

      const ev = (await eventsOf(sig)).find((e) => e.name === "escrowReleased");
      assert.ok(ev, "EscrowReleased emitted");
      assert.equal((ev!.data.amount as BN).toNumber(), PRICE);
      assert.ok((ev!.data.seller as PublicKey).equals(seller.publicKey));
    });

    it("can't release twice", async () => {
      const sellerBefore = await balance(ata(seller.publicKey));
      await expectErr(releaseTx(listing).rpc(), CLOSED);
      assert.equal(await balance(ata(seller.publicKey)), sellerBefore);
    });

    it("can't refund after release", async () => {
      await expectErr(refundTx(listing).rpc(), CLOSED);
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

      await expectErr(releaseTx(l2).rpc(), CLOSED);
      assert.equal(await balance(ata(seller.publicKey)), sellerBefore + PRICE);
    });
  });

  // --------------------------------------------------- refund_after_deadline

  describe("refund_after_deadline", () => {
    it("after the deadline anyone can refund the buyer; escrow closes, listing reopens, seller.refunded += 1", async () => {
      const listing = await createListing();
      const escrow = await fund(listing, (await chainNow()) + 3);

      await expectErr(refundTx(listing).rpc(), "DeadlineNotReached");

      const buyerBefore = await balance(ata(buyer.publicKey));
      const repSellerBefore = await rep(seller.publicKey);
      const repBuyerBefore = await rep(buyer.publicKey);

      // Wait for the validator clock to pass the deadline.
      let sig: string | undefined;
      for (let i = 0; i < 30 && !sig; i++) {
        await new Promise((r) => setTimeout(r, 1000));
        try {
          sig = await refundTx(listing).rpc();
        } catch (e: any) {
          if (e?.error?.errorCode?.code !== "DeadlineNotReached") throw e;
        }
      }
      assert.ok(sig, "refund never became possible");

      assert.equal(await balance(ata(buyer.publicKey)), buyerBefore + PRICE);
      assert.isFalse(await exists(ata(escrow)), "vault closed");
      assert.isFalse(await exists(escrow), "escrow closed");

      const l = await program.account.listing.fetch(listing);
      assert.deepEqual(l.status, { open: {} }, "listing back on the market");

      const rs = await rep(seller.publicKey);
      assert.equal(rs.refunded, repSellerBefore.refunded + 1);
      assert.equal(rs.completed, repSellerBefore.completed);
      assert.deepEqual(await rep(buyer.publicKey), repBuyerBefore);
      assert.ok((await eventsOf(sig!)).some((e) => e.name === "escrowRefunded"));

      // No double refund, no release after refund.
      await expectErr(refundTx(listing).rpc(), CLOSED);
      await expectErr(releaseTx(listing).rpc(), CLOSED);
      assert.equal(await balance(ata(buyer.publicKey)), buyerBefore + PRICE);

      // The reopened listing can be funded again and completed.
      await fund(listing);
      await releaseTx(listing).rpc();
      assert.isFalse(await exists(listing));
    });
  });

  // ------------------------------------------------------------- trade cost

  describe("cost of a trade", () => {
    // Like the app: each user signs and pays the fee for their own transaction.
    async function sendAs(builder: { transaction(): Promise<anchor.web3.Transaction> }, signer: Keypair) {
      const tx = await builder.transaction();
      tx.feePayer = signer.publicKey;
      return anchor.web3.sendAndConfirmTransaction(conn, tx, [signer], { commitment: "confirmed" });
    }

    it("a full trade between existing users costs only tx fees (all rent refunded)", async () => {
      // Reputation accounts + seller ATA already exist from earlier trades.
      const before = (await lamports(seller.publicKey)) + (await lamports(buyer.publicKey));

      const { listing, builder } = createListingTx();
      await sendAs(builder, seller); //                                        seller: 1 sig
      await sendAs(fundTx(listing, (await chainNow()) + 3600), buyer); //       buyer: 1 sig
      await sendAs(releaseTx(listing), buyer); //                               buyer: 1 sig

      const after = (await lamports(seller.publicKey)) + (await lamports(buyer.publicKey));
      assert.equal(before - after, 3 * 5000, "net cost = 3 signatures × 5000 lamports");
      assert.isFalse(await exists(listing));
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
      await cancelTx(a).rpc();
      const b = await createListing();
      await fund(b);
      const after = [await rep(seller.publicKey), await rep(buyer.publicKey)];
      assert.deepEqual(after, before);
    });

    it("a fresh wallet has no reputation account until it trades", async () => {
      assert.isFalse(await exists(repPda(stranger.publicKey)));
    });
  });
});
