# android

Kotlin + Jetpack Compose app. It uses Mobile Wallet Adapter (MWA) for signing,
one ViewModel per screen, and manual DI through `AppContainer`. The app never
holds keys. It reads chain state straight from RPC for anything that decides
what the user sees about money.

```
app/src/main/java/app/taptrade/
  TapTradeApp.kt        AppContainer: wallet, backend, rpc, tapTransport
  MainActivity.kt       MWA ActivityResultSender, `solana:` deep links (the fake tap), navigation
  wallet/               WalletManager (MWA connect / signAndSend), Base58
  data/                 Models, BackendApi (+ Http / Fake), SolanaRpc (HTTP + accountSubscribe websocket)
  explain/              TxDecoder (legacy + v0), EscrowAccount, ReleaseChecks, ListingChecks
  tap/                  TapTransport, DeepLinkTapTransport, NfcTapTransport (stub)
  ui/listings/          Listings, Sell (describe → draft → review → sign)
  ui/detail/            Listing detail: reputation, fund, cancel
  ui/handoff/           Seller: publish + live escrow watch → Paid. Buyer: tap → explainer → sign
```

## The explainer (`explain/`)

Before every release signature the app:

1. Checks the tapped link's host is our backend, and that its escrow belongs to
   this listing. This happens before the buyer's address is sent anywhere.
2. Fetches the unsigned transaction and decodes it.
3. Reads the `Escrow` account and vault balance directly from RPC.
4. Requires all of the following:
   - the escrow is owned by the program and belongs to this listing
   - the buyer funded it and is the only signer
   - the transaction calls only TapTrade (plus a capped compute budget)
   - it contains exactly one `release`
   - all 12 accounts match the chain
   - amount = escrow = vault = listing price

If any rule fails, signing is blocked and the reasons are listed. The AI
sentence is display only. `ListingChecks` does the same for a drafted
`create_listing`.

Unit tests (`./gradlew :app:testDebugUnitTest`) run these checks against real
devnet transactions, both genuine and tampered.

## Config (`gradle.properties`)

| Property | Default | Notes |
|---|---|---|
| `taptrade.backendUrl` | `http://10.0.2.2:3000` | Emulator → host. Physical phone: the host's LAN IP. |
| `taptrade.rpcUrl` | `https://api.devnet.solana.com` | Confirmations, account reads, websocket. |
| `taptrade.fakeBackend` | `false` | `true` = hardcoded listings, UI only. |
| `taptrade.programId` / `taptrade.tusdcMint` | devnet values | |

## Build & run

See [docs/EMULATOR_SETUP.md](../docs/EMULATOR_SETUP.md): two x86 emulators plus
Solana Mobile's **fakewallet**, then `./gradlew :app:installDebug`.
