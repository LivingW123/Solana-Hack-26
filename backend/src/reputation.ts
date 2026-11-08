import type { PublicKey } from "@solana/web3.js";
import { program, repPda } from "./chain";

/** JSON shape served to the app (android `data/Models.kt` → `Reputation`). */
export interface ReputationView {
  wallet: string;
  completed: number;
  refunded: number;
  disputed: number;
  firstTradeTs: number | null;
  /** 0..100. */
  score: number;
  summary: string;
}

/**
 * Counters come straight from the on-chain `Reputation` PDA. The score is an
 * explainable formula, not a model output: completion rate with a Beta(1,1) prior,
 * so a brand-new wallet sits at 50 and moves toward its real rate as trades accrue.
 * (Phase 8 adds distinct-counterparty weighting and the LLM one-liner.)
 */
export async function getReputation(wallet: PublicKey): Promise<ReputationView> {
  const r = await program.account.reputation.fetchNullable(repPda(wallet));
  const completed = r?.completed ?? 0;
  const refunded = r?.refunded ?? 0;
  const disputed = r?.disputed ?? 0;
  const firstTradeTs = r && r.firstTradeTs.toNumber() > 0 ? r.firstTradeTs.toNumber() : null;
  const score = Math.round((100 * (completed + 1)) / (completed + refunded + disputed + 2));

  const total = completed + refunded + disputed;
  const since = firstTradeTs
    ? ` since ${new Date(firstTradeTs * 1000).toLocaleDateString("en-US", { month: "short", year: "numeric" })}`
    : "";
  const summary =
    total === 0
      ? "New wallet: no on-chain trades yet."
      : `${completed} completed, ${refunded} refunded${disputed ? `, ${disputed} disputed` : ""}${since}.`;

  return { wallet: wallet.toBase58(), completed, refunded, disputed, firstTradeTs, score, summary };
}
