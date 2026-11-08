import { BN, BorshCoder, EventParser, type IdlAccounts } from "@coral-xyz/anchor";
import { PublicKey } from "@solana/web3.js";
import { connection, escrowPda, program, programId } from "./chain";
import { env } from "./config";
import { recordRelease } from "./reputation";
import type { Taptrade } from "./idl/taptrade";

type ListingAccount = IdlAccounts<Taptrade>["listing"];
type EscrowAccount = IdlAccounts<Taptrade>["escrow"];

/** JSON shape served to the app (android `data/Models.kt` → `Listing`). Every field comes from chain. */
export interface ListingView {
  pubkey: string;
  seller: string;
  listingId: number;
  title: string;
  /** Token base units. */
  price: number;
  mint: string;
  status: "Open" | "Funded";
  itemHash: string;
  /** Present once Funded (from the on-chain Escrow account). */
  escrow?: string;
  buyer?: string;
  /** Unix seconds; after this anyone can refund the buyer. */
  deadline?: number;
}

export interface IndexedEvent {
  name: string;
  signature: string;
  slot: number;
  data: Record<string, string>;
}

function toView(pubkey: PublicKey, l: ListingAccount, e: EscrowAccount | null): ListingView {
  const funded = "funded" in l.status;
  return {
    pubkey: pubkey.toBase58(),
    seller: l.seller.toBase58(),
    listingId: l.listingId.toNumber(),
    title: l.title,
    price: l.price.toNumber(),
    mint: l.mint.toBase58(),
    status: funded ? "Funded" : "Open",
    itemHash: Buffer.from(l.itemHash).toString("hex"),
    ...(funded && e
      ? { escrow: escrowPda(pubkey).toBase58(), buyer: e.buyer.toBase58(), deadline: e.deadline.toNumber() }
      : {}),
  };
}

/** Event fields → strings (PublicKey / BN aren't JSON-friendly). */
function plain(data: Record<string, unknown>): Record<string, string> {
  return Object.fromEntries(
    Object.entries(data).map(([k, v]) => [k, v instanceof PublicKey || BN.isBN(v) ? (v as BN).toString() : String(v)]),
  );
}

/**
 * In-memory index of program accounts, rebuilt from chain on every poll — there is
 * no database, so a restart loses nothing. Program logs are subscribed to as well:
 * each event is recorded and triggers an immediate re-index.
 */
class Indexer {
  private listings = new Map<string, ListingView>();
  private inflight: Promise<void> | null = null;
  private lastRefresh = 0;
  readonly events: IndexedEvent[] = [];

  get size() {
    return this.listings.size;
  }

  get refreshedAt() {
    return this.lastRefresh;
  }

  /** All Open/Funded listings, newest first. Settled/cancelled listings are closed accounts, so they're gone. */
  all(): ListingView[] {
    return [...this.listings.values()].sort((a, b) => b.listingId - a.listingId);
  }

  refresh(): Promise<void> {
    this.inflight ??= (async () => {
      try {
        const [ls, es] = await Promise.all([program.account.listing.all(), program.account.escrow.all()]);
        const escrowByListing = new Map(es.map((e) => [e.account.listing.toBase58(), e.account]));
        const next = new Map<string, ListingView>();
        for (const { publicKey, account } of ls) {
          next.set(publicKey.toBase58(), toView(publicKey, account, escrowByListing.get(publicKey.toBase58()) ?? null));
        }
        this.listings = next;
        this.lastRefresh = Date.now();
      } finally {
        this.inflight = null;
      }
    })();
    return this.inflight;
  }

  /** Re-index first if the cache is older than `maxAgeMs`. */
  async fresh(maxAgeMs: number): Promise<void> {
    if (Date.now() - this.lastRefresh > maxAgeMs) await this.refresh();
  }

  start() {
    const tick = () => this.refresh().catch((e) => console.error("[indexer] refresh failed:", e.message ?? e));
    void tick();
    setInterval(tick, env.INDEX_INTERVAL_MS).unref();

    // Events trigger a re-index, debounced so a burst costs one getProgramAccounts round.
    let pending: NodeJS.Timeout | null = null;
    const soon = () => {
      pending ??= setTimeout(() => {
        pending = null;
        void tick();
      }, 750);
    };

    // A websocket reconnect can leave a duplicate subscription that delivers the same
    // logs twice; skip signatures we've already handled.
    const seen = new Set<string>();

    const parser = new EventParser(programId, new BorshCoder(program.idl));
    connection.onLogs(
      programId,
      (logs, ctx) => {
        if (logs.err || seen.has(logs.signature)) return;
        seen.add(logs.signature);
        if (seen.size > 500) seen.delete(seen.values().next().value!);
        for (const ev of parser.parseLogs(logs.logs)) {
          const entry = { name: ev.name, signature: logs.signature, slot: ctx.slot, data: plain(ev.data) };
          if (/^escrowReleased$/i.test(ev.name)) recordRelease(logs.signature, entry.data.buyer, entry.data.seller);
          this.events.unshift(entry);
          console.log(`[event] ${ev.name} ${logs.signature.slice(0, 12)}…`);
        }
        this.events.splice(100);
        soon();
      },
      "confirmed",
    );
  }
}

export const indexer = new Indexer();

/** One listing straight from chain (no cache): the app reloads right after its own transactions. */
export async function loadListing(pubkey: PublicKey): Promise<ListingView | null> {
  const l = await program.account.listing.fetchNullable(pubkey);
  if (!l) return null;
  const e = "funded" in l.status ? await program.account.escrow.fetchNullable(escrowPda(pubkey)) : null;
  return toView(pubkey, l, e);
}
