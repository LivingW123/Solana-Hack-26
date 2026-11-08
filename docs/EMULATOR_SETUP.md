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

## 5. Backend on this machine (Phase 4+)

The backend holds no keys, so it can run right next to the emulators. Needs Node 20+.

```bash
cd backend
cp .env.example .env      # PROGRAM_ID and TUSDC_MINT are already filled in
npm install
npm run dev               # :3000; check http://localhost:3000/health
```

Run it in its own terminal and stop it with Ctrl+C. On Windows, killing the parent
shell can leave `node` running on :3000 (the next start fails with `EADDRINUSE`
and the old code keeps serving). Fix: `taskkill /F /IM node.exe`, or find the PID
with `netstat -ano | findstr :3000`.

## 6. Phase 3 check

On **both** emulators: open TapTrade → **Connect wallet** → approve in
fakewallet → the address shows in the top bar → open a listing → open the
Handoff screen.

## 7. Phase 4 check: a full trade between the two emulators

The fakewallet accounts must hold devnet SOL + tUSDC (funded from the dev laptop
with `scripts/`: `npm run demo-prep -- <SELLER_ADDR> <BUYER_ADDR>`). Every "approve in fakewallet"
below takes **two** taps: **AUTHORIZE**, then **SEND TRANSACTION TO CLUSTER**.

1. **Seller (5554):** Connect wallet → **Sell** → title + price → approve in
   fakewallet. The listing appears on both phones (Refresh on the buyer).
2. **Buyer (5556):** open it → **Fund escrow** → approve. Status → Funded,
   "confirmed in X s".
3. **Seller:** open the listing → **Ready for handoff** → **Publish for tap**.
   The screen shows "● Watching the escrow on-chain".
4. **Fake tap:** from the repo root, in Git Bash: `scripts/tap.sh`. It reads the
   seller's published URL from emulator-5554's logcat and opens it on
   emulator-5556 as a `solana:` deep link. The buyer app jumps to the listing's
   Handoff screen with "Tap received" from any screen, even if it was closed.
   (The debug paste field + **Simulate tap** still works as a fallback.)
5. **Buyer:** **Sign release** → approve. Shows "Released ✓ · confirmed in X s".
6. **Seller:** flips to **Paid ✓** by itself, from the RPC websocket, with no backend involved.

## 9. Phase 7–9 checks

- `./gradlew :app:testDebugUnitTest`: ReleaseChecksTest + ListingChecksTest.
- **Phase 7:** Seller → **Sell** → "Describe it":
  `sell my calc textbook for 20 USDC by Friday` → **Draft listing** → review
  card (*Calc textbook · 20 tUSDC · Fri · textbooks*) → **Sign & list** → the
  listing appears on both phones. **Edit** opens the form prefilled. Seller can
  **Cancel listing** an Open one.
- **Phase 8:** Listing detail shows the seller's reputation with level and
  "How is this scored?". Handoff shows the other side's. After a release both
  phones show **Your reputation (updated on-chain)** with completed +1.
- Explorer links open in the emulator's browser.

## 8. Phase 6 check: the explainer blocks a tampered tap

Start the backend with the demo route: add `DEMO_TAMPER=1` to `backend/.env`
(or `DEMO_TAMPER=1 npm run dev` in Git Bash) and restart it. Optionally
set `LLM_API_KEY` in `backend/.env` for the AI sentence; without it you get a
plain sentence.

Unit test first (no emulator): `cd android && ./gradlew :app:testDebugUnitTest`.
It runs the rule checks against real devnet transactions.

Then on the emulators, with a funded listing and the seller on **Publish for tap**:

1. `scripts/tap.sh --tamper` → buyer shows **Blocked: don't sign this** with
   "✗ Calls only the TapTrade program · SPL Token: Approve (lets someone else
   spend your tokens)". There is no Sign button.
2. `scripts/tap.sh --wrong-host` → blocked with "✗ Link comes from TapTrade",
   before the app contacts that host.
3. `scripts/tap.sh` (the genuine URL; its nonce is still unused) → **Before you
   sign** card: one sentence ("Releases 2 tUSDC to 7vSe…NpJS for 'Mug'") above
   every rule ✓ → **Sign release** → seller **Paid ✓**.
