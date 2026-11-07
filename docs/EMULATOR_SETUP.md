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

Until Phase 4, `taptrade.fakeBackend=true` serves hardcoded listings.

## 5. Phase 3 check

On **both** emulators: open TapTrade → **Connect wallet** → approve in
fakewallet → the address shows in the top bar → open a listing → open the
Handoff screen.
