import { randomBytes } from "node:crypto";
import { HttpError } from "./errors";

/** Single-use handoff nonces, 2-minute TTL. In memory: losing them on restart just means "New code". */
const TTL_MS = 120_000;
const nonces = new Map<string, { escrow: string; expiresAt: number }>();

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

/** Throws unless `nonce` is live and was issued for `escrow`. Does not consume it. */
export function checkNonce(nonce: string, escrow: string): void {
  const n = nonces.get(nonce);
  if (!n || n.expiresAt <= Date.now()) throw new HttpError(410, "Handoff code expired or already used; ask the seller for a new one");
  if (n.escrow !== escrow) throw new HttpError(400, "Handoff code was issued for a different escrow");
}

export function consumeNonce(nonce: string): void {
  nonces.delete(nonce);
}
