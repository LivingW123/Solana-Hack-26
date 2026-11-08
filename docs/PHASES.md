# TapTrade — Build Phases

Each phase has an exit check. Don't start the next phase until the current one passes.
Stretch items come only after Phase 9.

## Phase 0 — Toolchain (one-time)
- [x] WSL2 + Ubuntu (Anchor doesn't support native Windows well)
- [x] Rust, Solana CLI 2.1.21, Anchor 0.31.1, Node 20+ (WSL is aarch64: Solana CLI built from source; Anchor via `cargo install`)
- [x] `solana config set --url devnet`, keypair (deployer `7viHTXv4fdseFHw66aHu7VvQGhjzVhoaBNammCXiJwjc`), funded via faucet.solana.com
- [x] Android SDK (cmdline-tools + JDK 17) + two AVDs (seller = emulator-5554, buyer = emulator-5556) — on the Intel x86 laptop (emulator unsupported on Windows ARM64); see `docs/EMULATOR_SETUP.md`

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

## Phase 3 — Android skeleton  ✅ done
- [x] Kotlin project (Compose, AGP 8.7.3, Gradle 8.11.1), MWA wallet connect (`WalletManager`; disconnect clears the MWA auth token)
- [x] Screens: Listings, Listing detail, Handoff (one ViewModel each)
- [x] `TapTransport` interface + `DebugTapTransport` + `NfcTapTransport` TODO stub, injected via `AppContainer`
- [x] Hardcoded backend calls (`FakeBackendApi`; `HttpBackendApi` ready for Phase 4)
- [x] Built + run on the Intel x86 laptop: AVDs Pixel 7 / API 35 google_apis x86_64 (WHPX), fakewallet "v1" flavor on both

**Exit:** Both emulators connect a wallet and show the screens. ✓ fakewallet (seed-phrase accounts — press GENERATE; the default "ephemeral" mode makes a new key per connection) seller `6aMZ8C4DZvgbmtDtvL75MHF84W4Dyx1rm9u2PLJtRA4y` (emulator-5554), buyer `8884mmGFfrXAcdFrTu2ispkB3MgQFfbQ1Jb1bAiyhUNv` (emulator-5556): 0.5 SOL + 1000 tUSDC each

## Phase 4 — Backend tx endpoints + indexer  ✅ done
- [x] Node/TS server on :3000 (Express), zod on every request body; holds no keys; every tx simulated before it's returned
- [x] Unsigned tx builders: `POST /tx/create-listing` (on-chain `title`), `GET/POST /tx/fund` (Solana Pay / Actions shape, `/actions.json`), `POST /tx/release/nonce`, `GET/POST /tx/release` (Solana Pay, 2-min nonces claimed by the first buyer to fetch; that buyer may re-fetch a fresh blockhash)
- [x] Indexer: `getProgramAccounts` + program-log events (`/events`, debounced re-index), in-memory, no DB; `GET /listings[/:pubkey]`, `GET /reputation/:wallet`
- [x] `npm run e2e`: full trade through the API on devnet with local test wallets — create 1.0 s, fund 1.0 s, release 1.9 s; websocket saw the escrow close at release confirmation; wrong wallet / reused nonce / self-buy / bad input rejected
- [x] App: Sell dialog, Fund, Sign release; seller watches the escrow account over RPC websocket → "Paid ✓" from chain (listing closed = paid, reopened = refunded); confirmation times shown
- [x] App: `taptrade.fakeBackend=false`; `SolanaRpc` (OkHttp) for confirmations + websocket; listings are Open/Funded only

**Exit (milestone):** Two emulators complete a full trade on devnet. ✓ "Phase4 test mug", 2 tUSDC: listed 1.1 s → funded 1.7 s → released 0.7 s; seller showed "Paid ✓" with no action (~2 s). Txs: create `2i9EKNKa…bPe1xpP`, fund `4Dwr4DA7…ASb51DHZi`, release `5D9HVyoz…FwvFdgm7cY9`

## Phase 5 — Fake tap  ✅ done
- [x] `solana:` intent filter on MainActivity (singleTask, `onNewIntent`) → `DeepLinkTapTransport` (holds the tap if no Handoff screen is listening; app navigates to the listing with that escrow)
- [x] `scripts/tap.sh` (no args = seller's last published URL from logcat; `<escrow> <nonce>`; `--url` for tampered URLs)
- [ ] (optional) relay: `/tap/publish`, `/tap/latest`: skipped, the deep link covers the demo
- [x] Follow-ups from the run: Connect button on Handoff/Detail (a tap that cold-starts the app lands disconnected), Fund shows Funded as soon as it's confirmed, Listings re-reads on resume

**Exit:** `tap.sh` triggers the release flow on the buyer emulator. ✓ Buyer on Handoff → release 0.9 s; buyer on Listings, and app force-stopped (cold start 1.4 s) → opens the right Handoff with the tap received; unknown escrow → "Tap ignored" toast. Seller "Paid ✓" by itself each time.

## Phase 6 — Explainer  ✅ done
- [x] Rule checks **in the Android app** (`explain/ReleaseChecks.kt`), against `Escrow` fetched straight from RPC: link host allowlist + escrow = listing's (checked before our address is sent anywhere), escrow owned by program and for this listing, buyer = me and sole signer, only TapTrade (+ capped compute budget) instructions, exactly one `release`, all 12 accounts match the chain, amount = escrow = vault = listing price. Decoder handles legacy + v0 (lookup tables rejected). JVM unit test on real devnet fixtures.
- [x] LLM sentence from backend `/ai/explain`: facts from tx + chain, structured outputs (zod), retry once, must contain the real amount, cached, template fallback; never consulted when a check failed. Verified in `npm run e2e` (fallback path; no key on the dev laptop)
- [x] App blocks signing when any rule fails (no signable bytes, reasons listed, server's claimed label shown for contrast); signs exactly the reviewed bytes
- [x] Blockhash expiry during a long review: Sign re-fetches (same code, same buyer) and re-runs every check if the review is > 40 s old; expiry errors auto re-check instead of dead-ending
- [x] Demo: `DEMO_TAMPER=1` route (release + hidden SPL Token approve, same label) + `scripts/tap.sh --tamper` / `--wrong-host`

**Exit (milestone):** A tampered URL is blocked, with reasons shown. ✓ Unit test 9/9. `tap.sh --tamper` → "Blocked: don't sign this · ✗ Calls only the TapTrade program · SPL Token: Approve…", no Sign button; `--wrong-host` → blocked before contacting the host; genuine tap → 9 rules ✓ + sentence → released in 1.1 s, seller "Paid ✓".

## Phase 7 — Natural-language listing  ← *backend verified on devnet; app awaiting emulator run*
- [x] `POST /ai/draft-listing` → LLM `{title, price_usdc, deadline_iso, category}` (structured outputs, retry once) or rule parser without a key → validated (price > 0, deadline future ≤ 30 days, title ≤ 64 B) → base units → unsigned `create_listing`. `npm test` (parser + validation) and e2e: "sell my calc textbook for 20 USDC by Friday" → "Calc textbook", 20 tUSDC, Fri, textbooks → on-chain → cancelled
- [~] Review screen before signing (Sell → "Describe it" → review card → Sign & list, or Edit → form); the phone checks the tx's title/price/mint/signer match the draft (`explain/ListingChecks.kt`, unit-tested on a real draft tx)
- [x] `POST /tx/cancel-listing` + Cancel listing button (seller, Open)

**Exit:** One sentence produces an on-chain listing. (✓ through the API on devnet)

## Phase 8 — Reputation  ← *backend verified on devnet; app awaiting emulator run*
- [x] `GET /reputation/:wallet`: on-chain counters + distinct counterparties (from the Reputation PDA's own settle history, cached per signature) → `100 × (C+1)/(C+refunded+2×disputed+2)`, C capped at 3 per counterparty (anti-farming; new wallet = 50), level, formula, LLM one-liner that must repeat the completed count (template fallback). e2e: 7 completed with 1 counterparty → counted 3 → 80/100
- [~] Shown on listing detail (seller), handoff (the other side's), and "Your reputation (updated on-chain)" after release on both phones; "How is this scored?" shows the formula; explorer link to the PDA

**Exit:** Counters update after a trade and show in the app.

## Phase 9 — Polish + demo rehearsal  ← *tooling done; rehearsal on the emulator machine*
- [ ] Run through the 3-minute demo script (`docs/DEMO.md`) end to end, twice
- [x] Explorer links (listing, fund/list/release txs, escrow history, reputation PDA), loading/error states, `scripts/demo-prep.ts` (top up demo wallets, show listings + reputation), README + demo script with recovery table

**Exit:** A clean demo run with no manual fixes.

## Stretch (after Phase 9 only)
- Jupiter any-token funding (swap leg mocked on devnet)
- Doc + example for reading reputation via CPI
- Photo-to-listing with a vision model
