# CLAUDE.md — TapTrade (working name)

Hackathon project: a campus marketplace where in-person trades settle through an
on-chain escrow on Solana. The buyer locks USDC in escrow, and a "tap" at the
handoff releases it to the seller in seconds. AI drafts trades, explains
transactions before signing, and summarizes on-chain reputation.

**Solana is the core, not an add-on.** The chain holds the money, the rules and
the history. AI reads that history and drafts actions. Only the user's signature
or the program's own rules ever move funds.

### Why Solana (every feature must serve one of these — no "chain bolted on")

| Strength | Where it shows up | Rule for contributors |
|---|---|---|
| **Speed** | Release confirms in <1 s while both people are standing there. Seller's phone watches the escrow account over RPC websocket and flips to "Paid" the moment the chain confirms. | The seller's "Paid" state comes from the chain, never from the backend. |
| **Low fees** | A $5–$20 trade is viable. Every settled trade closes its accounts and refunds rent, so net cost = tx fees (~0.000015 SOL, tested). | Don't add accounts that outlive a trade (except `Reputation`). |
| **On-chain ownership** | Funds sit in a program vault, not with us. Listings (incl. title) live on-chain; the marketplace can be rebuilt from chain data alone. | The backend stores nothing that isn't derivable from chain (except nonces / AI cache). |
| **Composability** | Pay with any SPL token (USDC). `Reputation` is a plain PDA other programs can read. Events (`ListingCreated`, `EscrowFunded`, `EscrowReleased`, …) let anyone index trades. Fund/release endpoints follow Solana Pay so any wallet works. | Use standards (SPL Token, Solana Pay, Actions) over custom protocols. |

**Trust model:** the backend is a convenience (tx builder, indexer, LLM), never
a source of truth. Anything that gates a signature — the explainer's rule
checks — runs **on the phone** against account data fetched straight from RPC.

## Hackathon constraints

- **No hardware.** All testing runs on two Android emulators. The NFC "tap" is
  simulated (see "Fake tap"). Do not write real NFC/HCE code beyond the
  interface stub.
- **Emulators run on a teammate's x86 Windows machine.** The main dev laptop
  is Windows on ARM64, where the Android Emulator isn't available. Program,
  scripts and backend work happen on the ARM laptop (WSL); Android build/run
  happens on the x86 machine. Setup: `docs/EMULATOR_SETUP.md`.
- **Devnet only.** Never use mainnet keys or funds.
- **Ship the demo path first.** Work through the build order below in order;
  do not start a stretch item until the demo path works end to end.

## Architecture

```
/anchor    Anchor workspace; program crate in anchor/programs/taptrade, tests in anchor/tests
/backend   TypeScript (Node) server: tx-request endpoints, indexer, AI endpoints
/android   Kotlin app: wallet (Mobile Wallet Adapter), screens, fake tap
/scripts   Dev helpers: airdrop, create test mint, fake-tap commands
/docs      Build phases (docs/PHASES.md) and other notes
```

- **Program (Anchor, devnet):** holds escrowed tokens, enforces deadlines,
  updates reputation counters.
- **Backend:** builds unsigned transactions (Solana Pay transaction requests),
  indexes program accounts + events, calls the LLM. Holds the LLM API key.
  Never holds user keys. Stateless apart from nonces and caches.
- **Android app:** connects a wallet through Mobile Wallet Adapter, shows
  listings and reputation, runs the explainer's rule checks **locally against
  RPC** before every signature, and watches escrow accounts over websocket.
- **AI:** hosted LLM API (fast/cheap tier), called only from the backend.
  Always returns strict JSON validated against a schema.

## On-chain spec

### Accounts (PDAs)

- `Listing` — seeds `["listing", seller, listing_id]`. Exists while Open/Funded.
  - `seller: Pubkey`, `listing_id: u64`, `price: u64` (token base units),
    `mint: Pubkey`, `title: String` (≤ 64 bytes, on-chain),
    `item_hash: [u8; 32]` (hash of off-chain description/photo),
    `status: Open | Funded`, `bump: u8`
- `Escrow` — seeds `["escrow", listing]`. Exists only while funds are locked.
  - `buyer`, `seller`, `listing`, `mint`, `vault: Pubkey`, `amount: u64`,
    `deadline: i64`, `bump: u8`
  - Vault: ATA of (escrow PDA, mint).
- `Reputation` — seeds `["rep", wallet]`. Never closed; readable by any program.
  - `completed: u32`, `refunded: u32`, `disputed: u32`, `first_trade_ts: i64`,
    `bump: u8`
  - Written **only** by the program inside `release` / `refund_after_deadline`.

### Instructions

| Instruction | Signer | Effect | Event |
|---|---|---|---|
| `create_listing(listing_id, price, mint, title, item_hash)` | seller | Creates `Listing` (Open) | `ListingCreated` |
| `cancel_listing` | seller | Only if Open; closes Listing (rent → seller) | `ListingCancelled` |
| `fund_escrow(deadline)` | buyer | Moves `price` into vault; Listing → Funded | `EscrowFunded` |
| `release` | buyer | Vault → seller; closes Escrow + vault (rent → buyer) and Listing (rent → seller); both reputations `completed += 1` | `EscrowReleased` |
| `refund_after_deadline` | anyone | Only after `deadline`; vault → buyer; closes Escrow + vault (rent → buyer); Listing → Open again; seller `refunded += 1` | `EscrowRefunded` |

### Invariants (must have tests)

- Funds leave the vault only via `release` (buyer signs) or
  `refund_after_deadline` (after deadline).
- No double release / double refund (the Escrow account is closed on settle).
- Wrong signer, wrong mint, wrong vault, or wrong amount → error.
- Reputation cannot be modified by any instruction other than the two above.
- A completed trade between existing users costs only tx fees (all rent refunded).

### Test token

Use our own SPL mint on devnet ("tUSDC", 6 decimals) created by
`scripts/create-mint.ts`, so we never depend on a faucet. Keep the mint address
in `.env` / app config so it can be swapped for real devnet USDC later.

## Tap flow (Solana Pay transaction request)

1. Seller's app shows "Ready for handoff" and produces a URL:
   `solana:https://<backend>/tx/release?escrow=<pubkey>&nonce=<one-time>`
2. The fake tap delivers that URL to the buyer's app (see below).
3. Buyer's app `POST`s its wallet address to the URL; backend returns an
   unsigned `release` transaction (base64) and a label.
4. **Explainer runs.** Rule checks run on the phone: decode the tx and compare
   it with the `Escrow` account fetched directly from RPC. The LLM sentence
   comes from the backend. If any rule fails, block.
5. Buyer signs through Mobile Wallet Adapter; app submits; show confirmation
   with elapsed time.
6. Seller's phone, subscribed to the escrow account over RPC websocket, sees it
   close and shows "Paid ✓" — no backend in that loop.

Nonces are single-use and expire after 2 minutes (stored in backend memory is
fine for the hackathon).

## Fake tap (no hardware)

The app depends on an interface, never on NFC directly:

```kotlin
interface TapTransport {
    fun publish(url: String)                 // seller side
    fun listen(onTap: (url: String) -> Unit) // buyer side
}
```

Debug implementations (pick one, both are fine):

- **Deep link via adb** — buyer app handles `solana:` URIs via an intent filter.
  ```
  adb -s emulator-5556 shell am start -a android.intent.action.VIEW \
    -d "solana:http://10.0.2.2:3000/tx/release?escrow=<ESCROW>&nonce=<NONCE>"
  ```
  `scripts/tap.sh <escrow> <nonce>` wraps this.
- **Relay server** — seller `POST /tap/publish`; buyer's "Tap" button calls
  `GET /tap/latest`. Emulators reach the host machine at `10.0.2.2`.

Leave a `NfcTapTransport` class as a TODO stub only.

## Backend API

| Method | Path | Purpose |
|---|---|---|
| POST | `/ai/draft-listing` | `{text}` → validated listing JSON → unsigned `create_listing` tx |
| GET | `/listings`, `/listings/:pubkey` | Indexed on-chain listings (title is on-chain) |
| POST | `/tx/release/nonce` | Seller: `{escrow, seller}` → Solana Pay release URL with one-time nonce |
| GET/POST | `/tx/release` | Solana Pay transaction request for `release` |
| GET/POST | `/tx/fund` | Unsigned `fund_escrow` tx; Solana Pay / Actions shape so any wallet (or a Blink) can buy |
| POST | `/ai/explain` | `{tx_base64, checks[]}` → `{summary}` (LLM sentence only; checks are done on the phone) |
| GET | `/reputation/:wallet` | Counters from chain + score + one-line summary |
| POST/GET | `/tap/publish`, `/tap/latest` | Debug relay for fake tap |

Indexer: poll `getProgramAccounts` for our program every few seconds (and/or
subscribe to program logs for events) and cache in memory. No database: if the
backend restarts, it rebuilds everything from chain.

## AI features (in priority order)

1. **Natural-language escrow** — "sell my calc textbook for 20 USDC by Friday"
   → LLM returns
   `{"title": str, "price_usdc": number, "deadline_iso": str, "category": str}`.
   Backend validates (price > 0, deadline in future, ≤ 30 days), converts to
   base units, builds the transaction. The user reviews before signing.
2. **Transaction explainer** — the **rule checks are the real protection**;
   the LLM only writes the human-readable sentence. Rules:
   - every instruction targets our program ID (plus compute-budget / token /
     associated-token programs where expected);
   - escrow, vault, seller and mint accounts match the on-chain `Escrow`;
   - amount equals `Escrow.amount`;
   - no unexpected token approvals or transfers;
   - the Solana Pay URL host is our backend (allowlist).
   The checks run in the Android app against RPC data. If any rule fails, the
   app blocks signing and lists the reasons; the LLM is not consulted.
3. **Reputation score** — computed from on-chain counters with a simple,
   explainable formula (e.g. weighted completion rate with a prior for new
   wallets). The LLM only writes a one-line summary of the numbers; it does not
   invent the score.

### AI rules

- Call the LLM from the backend only. No API key in the Android app.
- Strict JSON output; reject and retry once on invalid JSON, then fail gracefully.
- AI is never on the critical path of `release`: if the LLM is down, the rule
  checks still run and the app shows a plain fallback summary.
- The model never sees or produces private keys and never submits transactions.

## Build order

1. **Program**: accounts + 5 instructions + tests for every invariant → deploy
   to devnet. *Milestone: `anchor test` green.*
2. **Scripts**: airdrop SOL, create tUSDC mint, mint to test wallets.
3. **Android skeleton**: wallet connect (MWA), Listings / Listing detail /
   Handoff screens, hardcoded calls to backend.
4. **Backend tx endpoints**: `create_listing`, `fund`, `release` tx requests +
   indexer. *Milestone: two emulators complete a full trade on devnet.*
5. **Fake tap**: deep-link + `scripts/tap.sh`.
6. **Explainer** (rules first, then LLM sentence). *Milestone: tampered URL is
   blocked.*
7. **Natural-language listing**.
8. **Reputation** endpoint + display on listing and handoff screens.
9. **Polish + demo rehearsal.**

### Stretch (only after step 9)

- Any-token funding via Jupiter (pay in SOL, escrow in USDC). Jupiter routes
  are thin/absent on devnet, so mock the swap leg on devnet and note it in the
  demo.
- Publish reputation as a readable on-chain account other programs can CPI-read
  (already true by design; add a doc + example).
- Photo-to-listing with a vision model.

## Dev setup & commands

```bash
# Solana / Anchor
solana config set --url devnet
solana-keygen new -o ~/.config/solana/id.json   # if needed
solana airdrop 2
cd anchor && anchor build && anchor test && anchor deploy

# Backend
cd backend && npm install && npm run dev        # serves on :3000

# Emulators: run two AVDs (e.g. emulator-5554 = seller, emulator-5556 = buyer)
adb devices
./scripts/tap.sh <ESCROW_PUBKEY> <NONCE>
```

Wallet on the emulator: use a wallet app that supports Mobile Wallet Adapter,
or the Solana Mobile sample "fake wallet" app built for testing. Fund its
devnet address with `scripts/airdrop.ts` and `scripts/mint-tusdc.ts`.

Environment (`backend/.env`, never committed):

```
RPC_URL=https://api.devnet.solana.com
PROGRAM_ID=<after deploy>
TUSDC_MINT=<from create-mint>
LLM_API_KEY=<key>
```

## Conventions

- Rust: Anchor `#[account]` structs with explicit `space`; custom error enum;
  checked math only (`checked_add`, etc.).
- TypeScript: strict mode; zod schemas for every LLM response and request body.
- Kotlin: one ViewModel per screen; `TapTransport` injected so debug/release
  can swap implementations.
- Amounts are always integer base units in code; format to decimals only in UI.
- Commit `.env.example`, never `.env` or keypairs.

## Demo script (~3 min)

1. Seller types a sentence → listing appears on-chain (show explorer link).
2. Buyer opens listing → sees seller reputation → funds escrow.
3. Fake tap → explainer: "Releases 20 tUSDC to <seller> for 'Calc textbook'"
   → sign → confirmed in seconds.
4. Fake tap with a tampered URL → explainer blocks it with reasons.
5. Show both wallets' reputation counters updated on-chain.

## Out of scope

Real NFC/HCE, mainnet, dispute arbitration, buyer agents, KYC/fiat on-ramp,
user accounts beyond wallets.
