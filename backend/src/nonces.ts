import { randomBytes } from "node:crypto";
import { HttpError } from "./errors";

/**
 * Handoff nonces, 2-minute TTL, in memory (losing them on restart just means "New code").
 *
 * Single-use = single buyer: the first wallet that fetches the release tx claims the
 * nonce. That same wallet may fetch again until it expires: the release carries a
 * recent blockhash (~60–90 s on devnet), and the buyer's explainer may outlast it.
 * Settling twice is impossible anyway: `release` closes the Escrow account.
 */
const TTL_MS = 120_000;
const nonces = new Map<string, { escrow: string; expiresAt: number; claimedBy?: string }>();

function sweep(now = Date.now()) {
  for (const [k, v] of nonces) if (v.expiresAt <= now) nonces.delete(k);
}

export function issueNonce(escrow: string): { nonce: string; expiresAt: number } {
  sweep();
  const nonce = randomBytes(16).toString("hex");
  const expiresAt = Date.now() + TTL_MS;
  nonces.set(nonce, { escrow, expiresAt });
  return { nonce, expiresAt };
}

/** Throws unless `nonce` is live, was issued for `escrow`, and is unclaimed or claimed by `account`. */
export function checkNonce(nonce: string, escrow: string, account: string): void {
  const n = nonces.get(nonce);
  if (!n || n.expiresAt <= Date.now()) throw new HttpError(410, "Handoff code expired; ask the seller for a new one");
  if (n.escrow !== escrow) throw new HttpError(400, "Handoff code was issued for a different escrow");
  if (n.claimedBy && n.claimedBy !== account) throw new HttpError(409, "Handoff code was already used by another wallet");
}

/** Bind the nonce to the buyer who fetched a valid release. */
export function claimNonce(nonce: string, account: string): void {
  const n = nonces.get(nonce);
  if (n) n.claimedBy = account;
}
