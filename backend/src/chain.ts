import { AnchorProvider, type BN, Program } from "@coral-xyz/anchor";
import { getAssociatedTokenAddressSync } from "@solana/spl-token";
import { Connection, PublicKey, type Transaction, type VersionedTransaction } from "@solana/web3.js";
import { env } from "./config";
import idlJson from "./idl/taptrade.json";
import type { Taptrade } from "./idl/taptrade";

export const connection = new Connection(env.RPC_URL, "confirmed");
export const programId = new PublicKey(env.PROGRAM_ID);
export const tusdcMint = new PublicKey(env.TUSDC_MINT);
export const TUSDC_DECIMALS = 6;

if (idlJson.address !== env.PROGRAM_ID) {
  console.warn(`[chain] IDL address ${idlJson.address} != PROGRAM_ID ${env.PROGRAM_ID}; using PROGRAM_ID`);
}

/** The backend never signs: this wallet only exists to satisfy AnchorProvider. */
const noSigner: ConstructorParameters<typeof AnchorProvider>[1] = {
  publicKey: PublicKey.default,
  signTransaction: async <T extends Transaction | VersionedTransaction>(_tx: T): Promise<T> => {
    throw new Error("backend never signs");
  },
  signAllTransactions: async <T extends Transaction | VersionedTransaction>(_txs: T[]): Promise<T[]> => {
    throw new Error("backend never signs");
  },
};

export const program = new Program<Taptrade>(
  { ...(idlJson as Taptrade), address: env.PROGRAM_ID },
  new AnchorProvider(connection, noSigner, { commitment: "confirmed" }),
);

const u64le = (n: bigint) => {
  const b = Buffer.alloc(8);
  b.writeBigUInt64LE(n);
  return b;
};

export const listingPda = (seller: PublicKey, listingId: bigint) =>
  PublicKey.findProgramAddressSync([Buffer.from("listing"), seller.toBuffer(), u64le(listingId)], programId)[0];

export const escrowPda = (listing: PublicKey) =>
  PublicKey.findProgramAddressSync([Buffer.from("escrow"), listing.toBuffer()], programId)[0];

export const repPda = (wallet: PublicKey) =>
  PublicKey.findProgramAddressSync([Buffer.from("rep"), wallet.toBuffer()], programId)[0];

/** Vault = ATA of (escrow PDA, mint); the escrow PDA is off-curve. */
export const vaultFor = (escrow: PublicKey, mint: PublicKey) => getAssociatedTokenAddressSync(mint, escrow, true);

/** Base units → "20" / "8.5". UI formatting only; amounts stay integers everywhere else. */
export function formatUnits(amount: bigint | BN, decimals = TUSDC_DECIMALS): string {
  const s = amount.toString().padStart(decimals + 1, "0");
  const frac = s.slice(-decimals).replace(/0+$/, "");
  return frac ? `${s.slice(0, -decimals)}.${frac}` : s.slice(0, -decimals);
}

export const short = (k: PublicKey | string) => {
  const s = k.toString();
  return `${s.slice(0, 4)}…${s.slice(-4)}`;
};
