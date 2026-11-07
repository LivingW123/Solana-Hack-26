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

`tap.sh` (fake tap) arrives in Phase 5.
