import { PublicKey } from "@solana/web3.js";

/** An error with an HTTP status, rendered as `{ error }` by the server. */
export class HttpError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

export function parsePubkey(value: string, what: string): PublicKey {
  try {
    return new PublicKey(value);
  } catch {
    throw new HttpError(400, `Invalid ${what}: ${value}`);
  }
}
