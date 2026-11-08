# scripts

Devnet helpers. Run from WSL (Node 20+), inside `scripts/`:

```bash
npm install
npm run setup          # fund seller+buyer with SOL, create tUSDC (if needed), mint 1000 each, show balances
```

| Command | What it does |
|---|---|
| `npx tsx airdrop.ts <who>... [--sol 0.5]` | Tops each wallet up to `--sol` SOL. Uses the faucet; if it's rate-limited, transfers from the payer. |
| `npx tsx create-mint.ts [--force]` | Creates tUSDC (6 decimals, payer = mint authority) and writes `TUSDC_MINT` to `backend/.env`. No-op if it already exists. |
| `npx tsx mint-tusdc.ts <who>... [--amount 1000]` | Mints whole tUSDC to each wallet (creates the token account if needed). |
| `npx tsx balances.ts <who>...` | SOL + tUSDC balances. |

`<who>` is a base58 address (e.g. an emulator wallet) or a local test-wallet name
(`seller`, `buyer`, …). Names map to `scripts/.wallets/<name>-keypair.json`,
created on first use and gitignored.

Config comes from `backend/.env` (see `backend/.env.example`). The payer / mint
authority is `PAYER_KEYPAIR`, default `~/.config/solana/id.json` (the deployer).
Scripts refuse to run against a mainnet RPC URL.

Funding emulator wallets (Phase 3+):

```bash
npx tsx airdrop.ts <EMULATOR_WALLET_ADDRESS>
npx tsx mint-tusdc.ts <EMULATOR_WALLET_ADDRESS>
```

## Before a demo (`demo-prep`)

```bash
npm run demo-prep                       # emulator seller + buyer
npx tsx demo-prep.ts <addr>... [--sol 0.5] [--tusdc 1000]
```

Tops each wallet up to the targets, prints balances and reputation, and lists
open/funded listings (backend at `BACKEND=`, default `http://localhost:3000`).

## Fake tap (`tap.sh`)

Runs on the **emulator machine** (bash / Git Bash, `adb` on PATH or `ADB=…`).
It delivers the seller's Solana Pay URL to the buyer emulator as a `solana:`
deep link, which is what an NFC tap would hand over.

```bash
scripts/tap.sh                      # tap the seller's last "Publish for tap" URL (from emulator-5554's logcat)
scripts/tap.sh --tamper             # same URL via the DEMO_TAMPER route: hidden token approval (must be blocked)
scripts/tap.sh --wrong-host         # same URL on another host (blocked before the app contacts it)
scripts/tap.sh <ESCROW> <NONCE>     # build the URL from parts
scripts/tap.sh --url 'solana:…'     # any URL as-is (tampered-URL demo)
```

The buyer app opens the matching listing's Handoff screen by itself, even if it
was on another screen or closed. Override devices with `SELLER=` / `BUYER=`,
and the URL host with `BACKEND=`.
