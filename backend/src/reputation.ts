import { BorshCoder, EventParser } from "@coral-xyz/anchor";
import type { PublicKey } from "@solana/web3.js";
import * as z from "zod/v4";
import { connection, program, programId, repPda } from "./chain";
import { llmJson } from "./llm";

/** JSON shape served to the app (android `data/Models.kt` → `Reputation`). */
export interface ReputationView {
  wallet: string;
  /** The wallet's `Reputation` PDA (seeds ["rep", wallet]): the account the counters live in. */
  account: string;
  /** On-chain counters from the `Reputation` PDA (written only by `release` / `refund_after_deadline`). */
  completed: number;
  refunded: number;
  disputed: number;
  firstTradeTs: number | null;
  /** Distinct wallets this one settled trades with (from its on-chain event history). */
  distinctCounterparties: number;
  /** Completed trades that count toward the score: at most 3 per counterparty. */
  effectiveCompleted: number;
  /** 0..100. */
  score: number;
  level: "New" | "Building" | "Trusted" | "Caution";
  formula: string;
  summary: string;
  summarySource: "llm" | "fallback";
}

/** Each counterparty contributes at most this many completed trades (anti-farming). */
const PER_COUNTERPARTY_CAP = 3;
/** How much of a wallet's recent history to read for counterparties. */
const HISTORY_LIMIT = 60;

export const FORMULA =
  "score = 100 × (C + 1) / (C + refunded + 2×disputed + 2), where C = completed trades counting at most " +
  `${PER_COUNTERPARTY_CAP} per counterparty. A new wallet starts at 50.`;

const parser = new EventParser(programId, new BorshCoder(program.idl));
const summaryCache = new Map<string, { summary: string; source: "llm" | "fallback" }>();

/** Settle transactions already read: signature → {buyer, seller} of its EscrowReleased (null = none). */
const settles = new Map<string, { buyer: string; seller: string } | null>();

/** The indexer feeds live EscrowReleased events here, so recent trades cost no extra RPC. */
export function recordRelease(signature: string, buyer: string, seller: string) {
  settles.set(signature, { buyer, seller });
}

async function readSettle(signature: string) {
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const tx = await connection.getTransaction(signature, { commitment: "confirmed", maxSupportedTransactionVersion: 0 });
      let found: { buyer: string; seller: string } | null = null;
      for (const ev of parser.parseLogs(tx?.meta?.logMessages ?? [])) {
        if (ev.name === "escrowReleased" || ev.name === "EscrowReleased") {
          found = { buyer: (ev.data.buyer as PublicKey).toBase58(), seller: (ev.data.seller as PublicKey).toBase58() };
        }
      }
      settles.set(signature, found);
      return;
    } catch {
      await new Promise((r) => setTimeout(r, 500 * 2 ** attempt)); // public devnet RPC rate limits
    }
  }
}

/**
 * Completed trades per counterparty, read from chain: every settle writes this wallet's
 * Reputation PDA, so its signatures are exactly the settles, and the EscrowReleased event in
 * each names both sides. Each signature is read once and cached; if the RPC stays busy,
 * the counterparties known so far are used (a lower bound, so the score errs low).
 */
async function counterparties(wallet: PublicKey): Promise<Map<string, number> | null> {
  const key = wallet.toBase58();
  const sigs = await connection.getSignaturesForAddress(repPda(wallet), { limit: HISTORY_LIMIT }, "confirmed");
  const ok = sigs.filter((s) => !s.err).map((s) => s.signature);
  for (const sig of ok) if (!settles.has(sig)) await readSettle(sig);

  const known = ok.filter((s) => settles.has(s));
  if (ok.length > 0 && known.length === 0) return null;
  const per = new Map<string, number>();
  for (const sig of known) {
    const s = settles.get(sig);
    const other = s ? (s.buyer === key ? s.seller : s.seller === key ? s.buyer : null) : null;
    if (other) per.set(other, (per.get(other) ?? 0) + 1);
  }
  return per;
}

const SummarySchema = z.object({ summary: z.string().min(5).max(160) });

/**
 * Counters come straight from the on-chain `Reputation` PDA; the score is the formula
 * above (explainable, not a model output). The LLM only phrases the one-line summary of
 * these numbers, and must repeat the completed count; otherwise a template is used.
 */
export async function getReputation(wallet: PublicKey): Promise<ReputationView> {
  const r = await program.account.reputation.fetchNullable(repPda(wallet));
  const completed = r?.completed ?? 0;
  const refunded = r?.refunded ?? 0;
  const disputed = r?.disputed ?? 0;
  const firstTradeTs = r && r.firstTradeTs.toNumber() > 0 ? r.firstTradeTs.toNumber() : null;

  let per: Map<string, number> | null = new Map();
  if (completed > 0) {
    per = await counterparties(wallet).catch((e) => {
      console.warn(`[reputation] history for ${wallet.toBase58()} unavailable: ${e instanceof Error ? e.message : e}`);
      return null;
    });
  }
  const distinct = per?.size ?? 0;
  // History beyond HISTORY_LIMIT isn't read; counting what we can see is the conservative choice.
  // If history couldn't be read at all, count one trade (no diversity evidence) rather than zero.
  const seen = per ? [...per.values()].reduce((a, n) => a + Math.min(n, PER_COUNTERPARTY_CAP), 0) : 1;
  const effective = Math.min(completed, seen);
  const score = Math.round((100 * (effective + 1)) / (effective + refunded + 2 * disputed + 2));
  const level: ReputationView["level"] =
    refunded + disputed > effective && refunded + disputed > 0
      ? "Caution"
      : completed === 0
        ? "New"
        : effective >= 5 && score >= 80
          ? "Trusted"
          : "Building";

  const since = firstTradeTs
    ? ` since ${new Date(firstTradeTs * 1000).toLocaleDateString("en-US", { month: "short", year: "numeric", timeZone: "UTC" })}`
    : "";
  const fallback =
    completed + refunded + disputed === 0
      ? "New wallet: no on-chain trades yet."
      : `${completed} completed${per && distinct > 0 ? ` with ${distinct} different ${distinct === 1 ? "person" : "people"}` : ""}, ${refunded} refunded${disputed ? `, ${disputed} disputed` : ""}${since}.`;

  const facts = { completed, refunded, disputed, distinctCounterparties: distinct, score, level, firstTrade: since.trim() || null };
  const cacheKey = `${wallet.toBase58()}:${JSON.stringify(facts)}`;
  let summary = summaryCache.get(cacheKey);
  if (!summary) {
    const out =
      completed + refunded + disputed === 0
        ? null
        : await llmJson(
            SummarySchema,
            "You write a one-line (max 20 words), neutral summary of a campus marketplace wallet's on-chain trade record " +
              "for a buyer deciding whether to meet this person. Use only the numbers given; don't invent facts, " +
              'don\'t give advice, don\'t restate the score. Respond with JSON {"summary": "..."}.',
            JSON.stringify(facts),
            (v) => v.summary.includes(String(completed)),
          );
    summary = out ? { summary: out.summary, source: "llm" } : { summary: fallback, source: "fallback" };
    summaryCache.set(cacheKey, summary);
  }

  return {
    wallet: wallet.toBase58(),
    account: repPda(wallet).toBase58(),
    completed,
    refunded,
    disputed,
    firstTradeTs,
    distinctCounterparties: distinct,
    effectiveCompleted: effective,
    score,
    level,
    formula: FORMULA,
    summary: summary.summary,
    summarySource: summary.source,
  };
}
