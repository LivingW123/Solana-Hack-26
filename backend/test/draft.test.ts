/**
 * Offline checks for the rule-based draft parser + validation (no RPC, no LLM):
 *   npx tsx test/draft.test.ts
 */
import assert from "node:assert/strict";
import { parseWithRules, validate } from "../src/draft";

// Wednesday 2026-11-04 12:00 UTC
const now = new Date("2026-11-04T12:00:00Z");

const cases: [string, { title: RegExp; price: number; category: string; day?: string }][] = [
  ["sell my calc textbook for 20 USDC by Friday", { title: /^Calc textbook$/, price: 20, category: "textbooks", day: "2026-11-06" }],
  ["Selling a TI-84 calculator $35.5 tomorrow", { title: /^TI-84 calculator$/i, price: 35.5, category: "electronics", day: "2026-11-05" }],
  ["list desk lamp for 8 usdc", { title: /^Desk lamp$/, price: 8, category: "furniture", day: "2026-11-11" }],
  ["sell the mini fridge for 45 bucks in 3 days", { title: /^Mini fridge$/, price: 45, category: "furniture", day: "2026-11-07" }],
  ["sell my mug for 2 usdc by monday", { title: /^Mug$/, price: 2, category: "kitchen", day: "2026-11-09" }],
];

for (const [text, want] of cases) {
  const raw = parseWithRules(text, now);
  const v = validate(raw, now);
  assert.ok(typeof v !== "string", `${text}: ${v}`);
  if (typeof v === "string") continue;
  assert.match(v.title, want.title, text);
  assert.equal(v.priceUsdc, want.price, text);
  assert.equal(v.price, Math.round(want.price * 1e6), text);
  assert.equal(v.category, want.category, text);
  if (want.day) assert.equal(v.deadlineIso.slice(0, 10), want.day, text);
  console.log(`✓ "${text}" → "${v.title}", ${v.priceUsdc} tUSDC, ${v.deadlineIso.slice(0, 10)}, ${v.category}`);
}

// Validation rejects bad drafts whoever produced them.
const base = { title: "Thing", price_usdc: 5, deadline_iso: "2026-11-10T00:00:00Z", category: "other" as const };
assert.equal(validate({ ...base, price_usdc: 0 }, now), "price must be > 0");
assert.equal(validate({ ...base, deadline_iso: "2026-11-01T00:00:00Z" }, now), "deadline is in the past");
assert.equal(validate({ ...base, deadline_iso: "2026-12-30T00:00:00Z" }, now), "deadline is more than 30 days away");
assert.equal(validate({ ...base, title: "x".repeat(65) }, now), "title longer than 64 bytes");
assert.equal(typeof validate(parseWithRules("hello there", now), now), "string");
console.log("✓ validation rejects price ≤ 0, past / > 30-day deadlines, long titles, no price");
