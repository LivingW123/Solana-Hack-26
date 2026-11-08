# TapTrade

A campus marketplace where in-person trades settle through an on-chain escrow
on Solana. The buyer locks USDC in escrow, and a "tap" at the handoff releases
it to the seller in about a second. AI drafts listings, explains transactions
before you sign, and summarizes on-chain reputation. Only your signature or the
program's own rules ever move funds.

**Devnet only.** Program `GpbVJKqd8upWam5fskxxkfkkz28aFAxpbbLuNDzoGnR2`
([explorer](https://explorer.solana.com/address/GpbVJKqd8upWam5fskxxkfkkz28aFAxpbbLuNDzoGnR2?cluster=devnet)).

## Why Solana

| | What it buys us |
|---|---|
| **Speed** | Release confirms in about 1 s while both people stand there. The seller's "Paid ✓" comes from an RPC websocket on the escrow account, not from our server. |
| **Low fees** | A $5 trade is viable. Settled trades close their accounts and refund rent, so a trade costs only transaction fees. |
| **On-chain ownership** | Funds sit in a program vault. Listings, including the title, live on-chain; the backend index rebuilds from chain on restart. |
| **Composability** | Any SPL token. `Reputation` is a plain PDA other programs can read. Events for indexers. Solana Pay transaction requests and an Actions-shaped buy endpoint, so any wallet or Blink works. |

**Trust model:** the backend builds unsigned transactions, indexes and calls the
LLM. It holds no keys and is never the source of truth. Before every release
signature, the phone decodes the transaction and checks it against the escrow
it read from RPC itself. A tampered tap is blocked with reasons.

## Layout

| Dir | What |
|---|---|
| [`anchor/`](anchor) | Anchor program: `create_listing`, `cancel_listing`, `fund_escrow`, `release`, `refund_after_deadline` (27 tests) |
| [`backend/`](backend) | Node/TS: Solana Pay + Actions tx endpoints, indexer, `/ai/*` (Claude, with template fallbacks) |
| [`android/`](android) | Kotlin/Compose app: Mobile Wallet Adapter, on-phone explainer rule checks, fake NFC tap |
| [`scripts/`](scripts) | Devnet helpers: mint tUSDC, airdrop, `demo-prep`, `tap.sh` |
| [`docs/`](docs) | [Build phases](docs/PHASES.md), [emulator setup](docs/EMULATOR_SETUP.md), [demo script](docs/DEMO.md) |

## Run it

```bash
# Program (WSL)
cd anchor && anchor build && anchor test

# Backend: holds no keys
cd backend && cp .env.example .env && npm install && npm run dev   # :3000
npm test && npm run e2e        # offline draft tests; full trade on devnet through the API

# Wallets
cd scripts && npm install && npm run demo-prep

# App: see docs/EMULATOR_SETUP.md (two x86 emulators + fakewallet)
cd android && ./gradlew :app:testDebugUnitTest :app:installDebug
scripts/tap.sh                 # fake tap: deliver the seller's Solana Pay URL to the buyer
```
