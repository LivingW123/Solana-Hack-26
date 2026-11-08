import Anthropic from "@anthropic-ai/sdk";
import { zodOutputFormat } from "@anthropic-ai/sdk/helpers/zod";
import type * as z from "zod/v4";
import { env } from "./config";

/**
 * The only place the backend talks to the LLM. Every call asks for strict JSON
 * (structured outputs), validates it against the schema, retries once, and then
 * returns null so the caller can fall back to a plain template. The model never
 * sees keys and never builds or submits transactions.
 */
const apiKey = env.LLM_API_KEY ?? process.env.ANTHROPIC_API_KEY;
const client = apiKey ? new Anthropic({ apiKey, timeout: 8_000, maxRetries: 1 }) : null;

export const llmEnabled = client !== null;

export async function llmJson<S extends z.ZodType>(
  schema: S,
  system: string,
  user: string,
  accept: (value: z.infer<S>) => boolean = () => true,
): Promise<z.infer<S> | null> {
  if (!client) return null;
  for (let attempt = 1; attempt <= 2; attempt++) {
    try {
      const res = await client.messages.parse({
        model: env.LLM_MODEL,
        max_tokens: 1024,
        system,
        messages: [{ role: "user", content: user }],
        output_config: { effort: "low", format: zodOutputFormat(schema) },
      });
      if (res.stop_reason === "refusal") return null;
      const parsed = res.parsed_output;
      if (parsed != null) {
        const checked = schema.safeParse(parsed);
        if (checked.success && accept(checked.data)) return checked.data;
      }
      console.warn(`[llm] attempt ${attempt}: output rejected (stop_reason ${res.stop_reason})`);
    } catch (e) {
      if (e instanceof Anthropic.AuthenticationError || e instanceof Anthropic.BadRequestError) {
        console.error(`[llm] ${e.status}: ${e.message}; using fallback`);
        return null;
      }
      console.warn(`[llm] attempt ${attempt} failed:`, e instanceof Anthropic.APIError ? `${e.status} ${e.message}` : e);
    }
  }
  return null;
}
