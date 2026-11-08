# TapTrade demo (~3 min)

Two Android emulators: **Seller** (emulator-5554, left) and **Buyer**
(emulator-5556, right). Both have fakewallet and TapTrade installed. Devnet only.

## Before you go on (5 min)

On the **dev laptop** (WSL), top up both demo wallets and see what's on-chain:

```bash
cd scripts && npm run demo-prep          # seller + buyer → 0.5 SOL, 1000 tUSDC each
```

On the **emulator machine**:

```bash
cd backend && npm run dev                # backend/.env: DEMO_TAMPER=1 (+ LLM_API_KEY for AI sentences)
curl localhost:3000/health               # "demoTamper": true, "llm": "claude-haiku-5-5" or null
```

- Both emulators: open TapTrade → **Connect wallet** → AUTHORIZE (fakewallet
  must be on its *seed-phrase* account, not "ephemeral").
- Seller: clear old **Open** listings (open each → **Cancel listing**).
- Open the [program on Solana Explorer](https://explorer.solana.com/address/GpbVJKqd8upWam5fskxxkfkkz28aFAxpbbLuNDzoGnR2?cluster=devnet) in a browser tab.
- fakewallet asks twice per transaction: **AUTHORIZE**, then **SEND TRANSACTION TO CLUSTER**.

## The script

**0:00 — The pitch (15 s).** "Campus trades are cash or trust. TapTrade puts the
money in a Solana escrow: the buyer locks USDC, and a tap at the handoff releases
it in about a second. Fees are a fraction of a cent, so a $5 trade is worth doing.
The chain holds the money, the rules and the history. AI drafts, explains and
summarizes, but never moves funds."

**0:15 — Sell in one sentence (35 s).** *Seller:* **Sell** → type
`sell my calc textbook for 20 USDC by Friday` → **Draft listing**.
- The review card shows *Calc textbook · 20 tUSDC · hand off by Fri · textbooks*.
- "The AI drafted it; the backend validated it (price > 0, deadline within 30
  days). Before the wallet opens, my phone checks the transaction says exactly
  this."
- **Sign & list** → "Listed on-chain · confirmed in ~1 s" → **View transaction ↗**.
  The title is on-chain; the marketplace can be rebuilt from chain data alone.

**0:50 — Buy (30 s).** *Buyer:* the listing appears → open it.
- Point at **Seller reputation**: "Counters live in an on-chain account any
  program can read. The score is a formula you can open (**How is this
  scored?**). Repeat trades with the same person count at most 3 times, so you
  can't farm it."
- **Fund escrow · 20 tUSDC** → "confirmed in ~1 s". The funds now sit in a
  program vault, not with us.

**1:20 — The handoff (35 s).** *Seller:* open the listing → **Ready for
handoff** → **Publish for tap**. It shows "● Watching the escrow on-chain".
*Buyer:* **Meet seller · wait for tap**.
- Run `scripts/tap.sh` (the fake NFC tap).
- *Buyer:* "Before you sign: Releases 20 tUSDC to 6aMZ…RA4y for 'Calc
  textbook'", with every rule ✓. "These checks run on my phone, against the
  escrow it read from Solana itself. The AI only wrote the sentence."
- **Sign release** → "Released ✓ · confirmed in ~1 s".
- *Seller:* **Paid ✓** appears by itself. "That came from a websocket on the
  escrow account. Our server isn't in that loop."

**1:55 — The attack (40 s).** Do a second quick trade (Sell "mug for 2 USDC" →
Fund → Publish), or pre-fund one before the demo. Then run
`scripts/tap.sh --tamper`.
- *Buyer:* **Blocked: don't sign this.** "✗ Calls only the TapTrade program ·
  SPL Token: Approve (lets someone else spend your tokens)."
- "The link claimed: 'Release 2 tUSDC…'. Same server, same label, but hidden in
  the transaction is an approval that would let an attacker drain the wallet.
  The phone decoded it and refused. No Sign button, and the AI wasn't asked."
- Optional: `scripts/tap.sh --wrong-host` gets blocked before the app even
  contacts that host.
- Then `scripts/tap.sh` with the genuine URL → sign → **Paid ✓**.

**2:35 — Reputation moved on-chain (20 s).** Both phones show **Your reputation
(updated on-chain)**, with completed +1. Tap **Counters on-chain ↗**: "The program
wrote this; no server can edit it."

**2:55 — Close (5 s).** "Speed for the handoff, low fees for small trades,
on-chain ownership of funds and listings, composable reputation. That's
Solana-native."

## If something goes wrong

| Symptom | Fix |
|---|---|
| "Handoff code expired" | Seller: **New code** → **Publish for tap** → `scripts/tap.sh` again |
| Buyer shows "Connect wallet" after a tap cold-started the app | Tap **Connect wallet** in the top bar; the tap is kept |
| fakewallet shows a new address | It's on *ephemeral*: GENERATE the seed account again, then fund it with `demo-prep` |
| "Not enough tUSDC" | `npm run demo-prep` on the dev laptop |
| Backend `EADDRINUSE` / old code | `taskkill /F /IM node.exe`, then `npm run dev` |
| Public RPC 429s / slow | Set `RPC_URL` in `backend/.env` and `taptrade.rpcUrl` in `android/gradle.properties` to a dedicated devnet RPC |
