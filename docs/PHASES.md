# TapTrade — Build Phases

Each phase has an exit check. Don't start the next phase until the current one passes.
Stretch items come only after Phase 9.

## Phase 0 — Toolchain (one-time)
- [x] WSL2 + Ubuntu (Anchor doesn't support native Windows well)
- [x] Rust, Solana CLI 2.1.21, Anchor 0.31.1, Node 20+ (WSL is aarch64: Solana CLI built from source; Anchor via `cargo install`)
- [x] `solana config set --url devnet`, keypair (deployer `7viHTXv4fdseFHw66aHu7VvQGhjzVhoaBNammCXiJwjc`), funded via faucet.solana.com
- [ ] Android Studio + two AVDs (seller = emulator-5554, buyer = emulator-5556) — on the teammate's x86 Windows machine (emulator unsupported on Windows ARM64); see `docs/EMULATOR_SETUP.md`

**Exit:** `anchor --version` and `solana --version` work in WSL; `adb devices` shows both emulators.

## Phase 1 — On-chain program  ✅ done
- [x] Anchor workspace in `/anchor` (crate: `anchor/programs/taptrade`)
- [x] Accounts: `Listing`, `Escrow` (+ ATA vault owned by escrow PDA), `Reputation`
- [x] Instructions: `create_listing`, `cancel_listing`, `fund_escrow`, `release`, `refund_after_deadline`
- [x] Tests for every invariant (vault exits, no double settle, wrong signer/mint/vault/seller, amounts, reputation write paths)
- [x] `anchor build` + `anchor keys sync` + `anchor test` green (27 passing). Revision: on-chain title, accounts closed on settle (net trade cost = tx fees), events, listing reopens after refund — upgraded on devnet (tx `4TrCyc7P…eXoZjEBVW`). PROGRAM_ID = `GpbVJKqd8upWam5fskxxkfkkz28aFAxpbbLuNDzoGnR2`
- [x] `anchor deploy --provider.cluster devnet` — PROGRAM_ID `GpbVJKqd8upWam5fskxxkfkkz28aFAxpbbLuNDzoGnR2` (deploy tx `3cRMeKLN23YbVBPpZ9CsJsHMMqvph1AgzUyQY2w3kQxpLNTue9YynTTrUehWwVXnG3GCXPj6w9w79bsefgskyWVg`)

**Exit:** `anchor test` green; program deployed to devnet.

## Phase 2 — Scripts  ✅ done
- [x] `scripts/airdrop.ts` (SOL to test wallets; falls back to a payer transfer when the faucet is rate-limited)
- [x] `scripts/create-mint.ts` (tUSDC, 6 decimals → `backend/.env`) — TUSDC_MINT `EyzrmDn24RkXBitBu3mPm17WimXQDEz9sydJP2AfB2X5`
- [x] `scripts/mint-tusdc.ts` (mint to seller/buyer) + `scripts/balances.ts`; `npm run setup` does all of it
- [x] `backend/.env.example`

**Exit:** Two devnet wallets each hold SOL and tUSDC. ✓ seller `7vSeVcn4BV59Dy3AUkJuUoaiK4yaCzs6HFyGaKrkNpJS`, buyer `94qGDCxhkRiKorFp4VBoaryXwMPCE4P1RwyVSLtBtPBQ`: 0.5 SOL + 1000 tUSDC each

## Phase 3 — Android skeleton  ← *code written, not yet built*
- [~] Kotlin project (Compose, AGP 8.7.3, Gradle 8.11.1), MWA wallet connect (`WalletManager`)
- [~] Screens: Listings, Listing detail, Handoff (one ViewModel each)
- [~] `TapTransport` interface + `DebugTapTransport` + `NfcTapTransport` TODO stub, injected via `AppContainer`
- [~] Hardcoded backend calls (`FakeBackendApi`; `HttpBackendApi` ready for Phase 4)
- [ ] Build + run on the teammate's x86 Windows machine (docs/EMULATOR_SETUP.md) — Android Emulator does not run on Windows ARM64

**Exit:** Both emulators connect a wallet and show the screens.

## Phase 4 — Backend tx endpoints + indexer
- [ ] Node/TS server on :3000, zod on every request body
- [ ] Unsigned tx builders: `create_listing` (with on-chain `title`), `GET/POST /tx/fund` (Solana Pay / Actions shape → any wallet or a Blink can buy), `POST /tx/release/nonce`, `GET/POST /tx/release` (Solana Pay, single-use 2-min nonces)
- [ ] Indexer: `getProgramAccounts` + program-log events, in-memory cache, no DB (rebuildable from chain); `GET /listings[/:pubkey]`
- [ ] App: seller watches the escrow account over RPC websocket → "Paid ✓" comes from the chain; buyer shows confirmation time
- [ ] App: `taptrade.fakeBackend=false`; listings are Open/Funded only (settled listings are closed accounts)

**Exit (milestone):** Two emulators complete a full trade on devnet.

## Phase 5 — Fake tap
- [ ] `solana:` intent filter on buyer app → `DeepLinkTapTransport`
- [ ] `scripts/tap.sh <escrow> <nonce>`
- [ ] (optional) relay: `/tap/publish`, `/tap/latest`

**Exit:** `tap.sh` triggers the release flow on the buyer emulator.

## Phase 6 — Explainer
- [ ] Rule checks **in the Android app**, against `Escrow` fetched straight from RPC: program IDs, escrow/vault/seller/mint, amount, no stray approvals/transfers, URL host allowlist
- [ ] LLM sentence from backend `/ai/explain` (strict JSON, retry once, plain fallback; never gates signing)
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
