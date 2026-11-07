# android

Kotlin + Jetpack Compose app. Mobile Wallet Adapter (MWA) for the wallet, one
ViewModel per screen, `TapTransport` injected via `AppContainer`.

```
app/src/main/java/app/taptrade/
  TapTradeApp.kt        AppContainer (manual DI): wallet, backend, tapTransport
  MainActivity.kt       creates the MWA ActivityResultSender, hosts navigation
  wallet/               WalletManager (MWA connect / signAndSend), Base58
  tap/                  TapTransport, DebugTapTransport (fake tap), NfcTapTransport (TODO stub)
  data/                 Models, BackendApi, HttpBackendApi, FakeBackendApi (hardcoded data)
  ui/listings/          Listings screen + ViewModel
  ui/detail/            Listing detail screen + ViewModel (seller reputation, fund escrow)
  ui/handoff/           Handoff screen + ViewModel (seller: publish URL; buyer: wait for tap)
```

## Config (`gradle.properties`)

| Property | Default | Notes |
|---|---|---|
| `taptrade.backendUrl` | `http://10.0.2.2:3000` | Emulator → host. Physical phone: host LAN IP. |
| `taptrade.fakeBackend` | `true` | Hardcoded listings until the Phase 4 backend exists. |
| `taptrade.programId` / `taptrade.tusdcMint` | devnet values | From `backend/.env`. |

Override per machine in `~/.gradle/gradle.properties` or `-Ptaptrade.backendUrl=…`.

## Build & run

Open `android/` in Android Studio (or `./gradlew :app:installDebug` with a device attached).

A wallet app that speaks MWA must be installed on each device. For testing, build
Solana Mobile's **fakewallet** from
[solana-mobile/mobile-wallet-adapter](https://github.com/solana-mobile/mobile-wallet-adapter)
(`android/` → `./gradlew :fakewallet:installDebug`). Fund the wallet address it shows with
`scripts/` (`npx tsx airdrop.ts <addr>` and `npx tsx mint-tusdc.ts <addr>`).

## Skeleton walkthrough (fake backend)

1. Listings → **Connect wallet** → approve in the wallet app → your address shows in the top bar.
2. "Mini fridge" is *your* funded listing → **Ready for handoff** → seller URL with a one-time nonce → **Publish for tap**.
3. "Desk lamp" is funded *by you* → **Meet seller · wait for tap** → paste a URL into the debug field → **Simulate tap**.
4. Open listings show **Fund escrow** (needs the real backend; Phase 4).
