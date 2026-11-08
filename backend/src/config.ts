import path from "node:path";
import dotenv from "dotenv";
import { z } from "zod";

dotenv.config({ path: path.resolve(__dirname, "../.env") });

/** Treat `KEY=` (empty) as unset. */
const optional = <T extends z.ZodTypeAny>(schema: T) =>
  z.preprocess((v) => (v === "" ? undefined : v), schema.optional());

const Env = z.object({
  RPC_URL: z.string().url().default("https://api.devnet.solana.com"),
  PROGRAM_ID: z.string().min(32),
  TUSDC_MINT: z.string().min(32),
  PORT: z.coerce.number().int().positive().default(3000),
  /** Base URL phones use to reach us (goes into Solana Pay links). Default: the request's own host. */
  PUBLIC_URL: optional(z.string().url()),
  /** Escrow deadline set at funding time; the program caps it at 30 days. */
  FUND_DEADLINE_HOURS: z.coerce.number().positive().max(720).default(48),
  /** Background re-index; program-log events also trigger one, so this is only a safety net. */
  INDEX_INTERVAL_MS: z.coerce.number().int().min(1000).default(15000),
  /** Anthropic API key for the explainer sentence / summaries. Unset → template fallbacks only. */
  LLM_API_KEY: optional(z.string()),
  /** Fast/cheap tier per CLAUDE.md. */
  LLM_MODEL: z.string().default("claude-haiku-5-5"),
  /** Mounts /demo/evil/* (a release tx with a hidden token approval) for the tampered-tap demo. */
  DEMO_TAMPER: z
    .enum(["0", "1", "true", "false"])
    .default("0")
    .transform((v) => v === "1" || v === "true"),
});

export const env = Env.parse(process.env);

if (/mainnet/i.test(env.RPC_URL)) {
  throw new Error(`Refusing to run against mainnet RPC (${env.RPC_URL}). Devnet only.`);
}
