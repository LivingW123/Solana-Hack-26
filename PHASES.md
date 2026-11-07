# TapTrade — Build Phases

Each phase has an exit check. Don't start the next phase until the current one passes.
Stretch items come only after Phase 9.

## Phase 0 — Toolchain (one-time)
- [ ] WSL2 + Ubuntu (Anchor doesn't support native Windows well)
- [ ] Rust, Solana CLI 2.1.x, Anchor 0.31.1 (via AVM), Node 20+
- [ ] `solana config set --url devnet`, keypair, `solana airdrop 2`
- [ ] Android Studio + two AVDs (seller = emulator-5554, buyer = emulator-5556)

**Exit:** `anchor --version` and `solana --version` work in WSL; `adb devices` shows both emulators.

## Phase 1 — On-chain program  ← *in progress*
- [x] Anchor workspace in `/program`
- [x] Accounts: `Listing`, `Escrow` (+ ATA vault owned by escrow PDA), `Reputation`
- [x] Instructions: `create_listing`, `cancel_listing`, `fund_escrow`, `release`, `refund_after_deadline`
- [x] Tests for every invariant (vault exits, no double settle, wrong signer/mint/vault/seller, amounts, reputation write paths)
- [ ] `anchor build` + `anchor keys sync` + `anchor test` green
- [ ] `anchor deploy --provider.cluster devnet`; record `PROGRAM_ID`

**Exit:** `anchor test` green; program deployed to devnet.

## Phase 2 — Scripts
- [ ] `scripts/airdrop.ts` (SOL to test wallets)
- [ ] `scripts/create-mint.ts` (tUSDC, 6 decimals → `.env`)
- [ ] `scripts/mint-tusdc.ts` (mint to seller/buyer)
- [ ] `.env.example`

**Exit:** Two devnet wallets each hold SOL and tUSDC.

## Phase 3 — Android skeleton
- [ ] Kotlin project, MWA wallet connect
- [ ] Screens: Listings, Listing detail, Handoff (one ViewModel each)
- [ ] `TapTransport` interface + `NfcTapTransport` TODO stub
- [ ] Hardcoded backend calls

**Exit:** Both emulators connect a wallet and show the screens.

## Phase 4 — Backend tx endpoints + indexer
- [ ] Node/TS server on :3000, zod on every request body
- [ ] Unsigned tx builders: `create_listing`, `POST /tx/fund`, `GET/POST /tx/release` (Solana Pay, single-use 2-min nonces)
- [ ] Indexer: poll `getProgramAccounts`, in-memory cache

**Exit (milestone):** Two emulators complete a full trade on devnet.

## Phase 5 — Fake tap
- [ ] `solana:` intent filter on buyer app → `DeepLinkTapTransport`
- [ ] `scripts/tap.sh <escrow> <nonce>`
- [ ] (optional) relay: `/tap/publish`, `/tap/latest`

**Exit:** `tap.sh` triggers the release flow on the buyer emulator.

## Phase 6 — Explainer
- [ ] Rule checks: program IDs, escrow/vault/seller/mint vs on-chain `Escrow`, amount, no stray approvals/transfers
- [ ] LLM sentence (strict JSON, retry once, plain fallback)
- [ ] App blocks signing when `ok: false`

**Exit (milestone):** A tampered URL is blocked, with reasons shown.

## Phase 7 — Natural-language listing
- [ ] `POST /ai/draft-listing` → validated JSON → unsigned `create_listing` tx
- [ ] Review screen before signing

**Exit:** One sentence produces an on-chain listing.

## Phase 8 — Reputation
- [ ] `GET /reputation/:wallet`: counters, explainable score (prior for new wallets), LLM one-liner
- [ ] Shown on the listing and handoff screens

**Exit:** Counters update after a trade and show in the app.

## Phase 9 — Polish + demo rehearsal
- [ ] Run through the 3-minute demo script end to end, twice
- [ ] Explorer links, loading/error states, reset script for fresh demo wallets

**Exit:** A clean demo run with no manual fixes.

## Stretch (after Phase 9 only)
- Jupiter any-token funding (swap leg mocked on devnet)
- Doc + example for reading reputation via CPI
- Photo-to-listing with a vision model
