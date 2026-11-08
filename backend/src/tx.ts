import { createHash } from "node:crypto";
import { BN } from "@coral-xyz/anchor";
import {
  ASSOCIATED_TOKEN_PROGRAM_ID,
  TOKEN_PROGRAM_ID,
  createApproveInstruction,
  getAssociatedTokenAddressSync,
} from "@solana/spl-token";
import {
  Keypair,
  PublicKey,
  SystemProgram,
  TransactionMessage,
  VersionedTransaction,
  type TransactionInstruction,
} from "@solana/web3.js";
import { connection, escrowPda, formatUnits, listingPda, program, repPda, short, tusdcMint, vaultFor } from "./chain";
import { env } from "./config";
import { HttpError } from "./errors";

export const MAX_TITLE_BYTES = 64;

/** Solana Pay / Actions response body. */
export interface TxResult {
  transaction: string;
  message: string;
}

/**
 * Build an unsigned legacy transaction with `payer` as fee payer, simulate it
 * (sigVerify off) so a doomed tx fails here with a readable reason instead of in
 * the wallet, and return it base64-encoded. The user's wallet signs and submits.
 */
async function unsignedTx(payer: PublicKey, ixs: TransactionInstruction[]): Promise<string> {
  const { blockhash } = await connection.getLatestBlockhash("confirmed");
  const message = new TransactionMessage({ payerKey: payer, recentBlockhash: blockhash, instructions: ixs }).compileToLegacyMessage();
  const tx = new VersionedTransaction(message);
  const sim = await connection.simulateTransaction(tx, { sigVerify: false, commitment: "confirmed" });
  if (sim.value.err) {
    const logs = sim.value.logs ?? [];
    const reason =
      logs.find((l) => l.includes("Error Message:"))?.split("Error Message:")[1]?.trim() ??
      logs.find((l) => /insufficient|failed/i.test(l)) ??
      JSON.stringify(sim.value.err);
    throw new HttpError(400, `Transaction would fail: ${reason}`);
  }
  return Buffer.from(tx.serialize()).toString("base64");
}

// ---------------------------------------------------------------------------
// create_listing
// ---------------------------------------------------------------------------

export async function buildCreateListing(args: {
  seller: PublicKey;
  title: string;
  price: bigint;
  description?: string;
}): Promise<TxResult & { listing: string; listingId: string }> {
  const title = args.title.trim();
  if (!title || Buffer.byteLength(title, "utf8") > MAX_TITLE_BYTES) {
    throw new HttpError(400, `Title must be 1–${MAX_TITLE_BYTES} bytes`);
  }
  if (args.price <= 0n) throw new HttpError(400, "Price must be > 0");

  // Unique per seller; ms timestamps also sort newest-first in the indexer.
  const listingId = BigInt(Date.now());
  const listing = listingPda(args.seller, listingId);
  // Integrity hash of the full (off-chain) description; the title itself is on-chain.
  const itemHash = createHash("sha256")
    .update(JSON.stringify({ title, description: args.description ?? "" }))
    .digest();

  const ix = await program.methods
    .createListing(new BN(listingId.toString()), new BN(args.price.toString()), tusdcMint, title, [...itemHash])
    .accountsPartial({
      seller: args.seller,
      listing,
      mintAccount: tusdcMint,
      systemProgram: SystemProgram.programId,
    })
    .instruction();

  return {
    transaction: await unsignedTx(args.seller, [ix]),
    message: `List "${title}" for ${formatUnits(args.price)} tUSDC`,
    listing: listing.toBase58(),
    listingId: listingId.toString(),
  };
}

// ---------------------------------------------------------------------------
// fund_escrow
// ---------------------------------------------------------------------------

export async function buildFund(listingKey: PublicKey, buyer: PublicKey): Promise<TxResult & { escrow: string }> {
  const l = await program.account.listing.fetchNullable(listingKey);
  if (!l) throw new HttpError(404, "Listing not found (sold or cancelled)");
  if (!("open" in l.status)) throw new HttpError(409, "Listing is already funded");
  if (l.seller.equals(buyer)) throw new HttpError(400, "You can't buy your own listing");

  const buyerToken = getAssociatedTokenAddressSync(l.mint, buyer);
  const balance = await connection
    .getTokenAccountBalance(buyerToken, "confirmed")
    .then((b) => BigInt(b.value.amount))
    .catch(() => 0n);
  const price = BigInt(l.price.toString());
  if (balance < price) {
    throw new HttpError(400, `Not enough tUSDC: need ${formatUnits(price)}, have ${formatUnits(balance)}`);
  }

  const escrow = escrowPda(listingKey);
  const deadline = Math.floor(Date.now() / 1000) + Math.round(env.FUND_DEADLINE_HOURS * 3600);
  const ix = await program.methods
    .fundEscrow(new BN(deadline))
    .accountsPartial({
      buyer,
      listing: listingKey,
      escrow,
      mint: l.mint,
      buyerToken,
      vault: vaultFor(escrow, l.mint),
      tokenProgram: TOKEN_PROGRAM_ID,
      associatedTokenProgram: ASSOCIATED_TOKEN_PROGRAM_ID,
      systemProgram: SystemProgram.programId,
    })
    .instruction();

  return {
    transaction: await unsignedTx(buyer, [ix]),
    message: `Lock ${formatUnits(price)} tUSDC in escrow for "${l.title}" (refundable after ${env.FUND_DEADLINE_HOURS}h)`,
    escrow: escrow.toBase58(),
  };
}

/** Listing title + price for the Actions GET metadata. */
export async function listingSummary(listingKey: PublicKey) {
  const l = await program.account.listing.fetchNullable(listingKey);
  if (!l) throw new HttpError(404, "Listing not found (sold or cancelled)");
  return { title: l.title, price: formatUnits(l.price), open: "open" in l.status, seller: l.seller.toBase58() };
}

// ---------------------------------------------------------------------------
// release
// ---------------------------------------------------------------------------

export async function loadEscrow(escrowKey: PublicKey) {
  const e = await program.account.escrow.fetchNullable(escrowKey);
  if (!e) throw new HttpError(404, "Escrow not found (already settled?)");
  return e;
}

export async function buildRelease(escrowKey: PublicKey, account: PublicKey): Promise<TxResult> {
  const { ix, message } = await releaseInstruction(escrowKey, account);
  return { transaction: await unsignedTx(account, [ix]), message };
}

/** Devnet demo only (DEMO_TAMPER=1): what a malicious server could hand the buyer. */
const ATTACKER = Keypair.generate().publicKey;

/**
 * A genuine `release` plus a hidden SPL Token `approve` that would let ATTACKER drain the
 * buyer's tUSDC later. Same innocent label as the real one. The phone's rule checks
 * (only the TapTrade program may be called) are what catch it.
 */
export async function buildTamperedRelease(escrowKey: PublicKey, account: PublicKey): Promise<TxResult> {
  const { ix, message, mint } = await releaseInstruction(escrowKey, account);
  const approve = createApproveInstruction(
    getAssociatedTokenAddressSync(mint, account),
    ATTACKER,
    account,
    BigInt("18446744073709551615"),
  );
  return { transaction: await unsignedTx(account, [ix, approve]), message };
}

async function releaseInstruction(escrowKey: PublicKey, account: PublicKey) {
  const e = await loadEscrow(escrowKey);
  if (!e.buyer.equals(account)) throw new HttpError(403, "Only the buyer who funded this escrow can release it");
  const l = await program.account.listing.fetch(e.listing);

  const ix = await program.methods
    .release()
    .accountsPartial({
      buyer: e.buyer,
      seller: e.seller,
      listing: e.listing,
      escrow: escrowKey,
      mint: e.mint,
      vault: e.vault,
      sellerToken: getAssociatedTokenAddressSync(e.mint, e.seller),
      buyerRep: repPda(e.buyer),
      sellerRep: repPda(e.seller),
      tokenProgram: TOKEN_PROGRAM_ID,
      associatedTokenProgram: ASSOCIATED_TOKEN_PROGRAM_ID,
      systemProgram: SystemProgram.programId,
    })
    .instruction();

  return { ix, mint: e.mint, message: `Release ${formatUnits(e.amount)} tUSDC to ${short(e.seller)} for "${l.title}"` };
}
