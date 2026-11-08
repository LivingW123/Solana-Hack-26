# Emulator machine setup

The Android emulators run on an **x86-64 (Intel/AMD) Windows** machine. The
Android Emulator doesn't run on Windows ARM64, so the ARM dev laptop can't host
them; it keeps doing the program, scripts and backend work.

## 1. Android Studio + SDK

1. Install Android Studio and run the setup wizard with **Standard**. It installs
   the SDK, platform-tools (`adb`) and the emulator.
2. Make sure CPU virtualization is on (BIOS: Intel VT-x / AMD-V), and that
   Windows has **Windows Hypervisor Platform** turned on in "Turn Windows
   features on or off".

## 2. Two emulators (AVDs)

In **Device Manager → +**, create two phones, e.g. Pixel 8, with an
**x86_64 Google APIs** image, API 34 or 35:

| AVD name | Role | adb serial (first launch order) |
|---|---|---|
| `Seller` | seller | `emulator-5554` |
| `Buyer`  | buyer  | `emulator-5556` |

Start Seller first, then Buyer, so the serials match the scripts.
Check with `adb devices`.

## 3. Wallet app on both emulators (Mobile Wallet Adapter)

Use Solana Mobile's test wallet, **fakewallet**:

```bash
git clone https://github.com/solana-mobile/mobile-wallet-adapter
cd mobile-wallet-adapter/android
./gradlew :fakewallet:assembleDebug
adb -s emulator-5554 install -r fakewallet/build/outputs/apk/debug/fakewallet-debug.apk
adb -s emulator-5556 install -r fakewallet/build/outputs/apk/debug/fakewallet-debug.apk
```

Open fakewallet on each emulator and note its address. Send both addresses to
whoever holds the deployer keypair. They run, from `scripts/`:

```bash
npx tsx airdrop.ts <SELLER_ADDR> <BUYER_ADDR>
npx tsx mint-tusdc.ts <SELLER_ADDR> <BUYER_ADDR>
```

## 4. TapTrade app

Open `android/` in Android Studio, or:

```bash
cd android
./gradlew :app:installDebug          # installs on every connected device
```

The backend URL is set in `android/gradle.properties` (`taptrade.backendUrl`):

- **Backend on this same machine** (simplest): keep `http://10.0.2.2:3000`.
  `10.0.2.2` is the emulator's alias for its host.
- **Backend on another machine**: use that machine's LAN IP, e.g.
  `-Ptaptrade.backendUrl=http://192.168.1.20:3000`. Allow port 3000 through
  that machine's firewall.

`taptrade.fakeBackend=true` serves hardcoded listings (UI only, no trading).

## 5. Backend

The backend holds no keys, so it can run right next to the emulators. Needs Node 20+.

```bash
cd backend
cp .env.example .env      # PROGRAM_ID and TUSDC_MINT are already filled in
npm install
npm run dev               # :3000; check http://localhost:3000/health
```

Optional `.env` settings: `DEMO_TAMPER=1` enables the tampered-tap demo route,
and `LLM_API_KEY` turns on AI drafts and sentences (rule-based fallbacks
otherwise).

On Windows, stop it with Ctrl+C in its own terminal. Killing the parent shell
can leave `node` holding :3000; fix with `taskkill /F /IM node.exe`.

## 6. Fund the emulator wallets

In fakewallet, press **GENERATE** on each emulator so it uses a persistent
seed-phrase account. The default "ephemeral" mode makes a new key per
connection. Then fund both addresses from a machine with the deployer key:

```bash
cd scripts && npm run demo-prep -- <SELLER_ADDR> <BUYER_ADDR>   # 0.5 SOL + 1000 tUSDC each
```

Every "approve in fakewallet" below takes two taps: **AUTHORIZE**, then
**SEND TRANSACTION TO CLUSTER**.

## 7. A full trade

1. **Seller (5554):** Connect wallet → **Sell something** → describe it, e.g.
   `sell my calc textbook for 20 USDC by Friday` → **Draft listing** → review
   card → **Sign & list**. **Edit** switches to a prefilled form.
2. **Buyer (5556):** open the listing → check the seller's reputation ("How is
   this scored?") → **Fund escrow**.
3. **Seller:** open the listing → **Ready for handoff** → **Publish for tap**.
4. **Tap:** from the repo root, in Git Bash, run `scripts/tap.sh`. It reads the
   seller's published link from emulator-5554's logcat and opens it on
   emulator-5556 as a `solana:` deep link. The buyer app jumps to the right
   listing, even if it was closed.
5. **Buyer:** the **Before you sign** card shows the explanation and every
   check ✓ → **Sign release** → **Released**.
6. **Seller:** flips to **Paid** by itself, from the RPC websocket. Both phones
   then show their reputation updated on-chain.

## 8. The attack: a tampered tap is blocked

With `DEMO_TAMPER=1` and a funded listing whose seller is on **Publish for tap**:

1. `scripts/tap.sh --tamper`: the same link, but the transaction hides an SPL
   Token `approve` to an attacker. The buyer sees **Blocked · Calls a program it
   shouldn't · SPL Token: Approve**. There is no Sign button.
2. `scripts/tap.sh --wrong-host`: blocked with "Link isn't from TapTrade"
   before the app contacts that host.
3. `scripts/tap.sh`: the genuine link still works.

## 9. Unit tests

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

These run the phone's transaction checks (release and create-listing) against
real devnet transactions in `src/test/resources`.
