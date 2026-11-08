# backend

TypeScript (Node 20+) server: Solana Pay transaction requests, Actions, indexer.
It is a convenience layer, never a source of truth. It holds **no keys**: every
transaction it builds is unsigned, and the user's wallet signs it. It stores
nothing that can't be rebuilt from chain (only handoff nonces, in memory).

```bash
cp .env.example .env    # PROGRAM_ID, TUSDC_MINT (scripts/create-mint writes it)
npm install
npm run dev             # :3000
npm run e2e             # full devnet trade through the API (needs scripts/.wallets seller + buyer, funded)
npm run typecheck
npm run sync-idl        # after `anchor build`: copy the IDL + types into src/idl
```

## API

| Method | Path | Body / query | Returns |
|---|---|---|---|
| GET | `/health` | | program, mint, slot, index size |
| GET | `/listings` | | Open + Funded listings from the index, newest first |
| GET | `/listings/:pubkey` | | One listing, read straight from chain (404 once settled or cancelled) |
| GET | `/events` | | Last 100 program events (`ListingCreated`, `EscrowFunded`, …) |
| GET | `/reputation/:wallet` | | On-chain counters + score (Beta(1,1) completion rate) + summary |
| POST | `/tx/create-listing` | `{seller, title, price, description?}` | `{transaction, message, listing, listingId}` |
| GET | `/tx/fund?listing=` | | Solana Actions metadata (Blink-ready) |
| POST | `/tx/fund?listing=` | `{account}` | `{type:"transaction", transaction, message, escrow}` |
| POST | `/tx/release/nonce` | `{escrow, seller}` | `{url: "solana:…/tx/release?escrow=…&nonce=…", expiresAt}` |
| GET | `/tx/release` | | Solana Pay `{label, icon}` |
| POST | `/tx/release?escrow=&nonce=` | `{account}` | Solana Pay `{transaction, message}`; nonce is single-use, 2 min |
| GET | `/actions.json` | | Actions discovery rules |

Notes:

- `price` is always integer base units (tUSDC has 6 decimals).
- `transaction` is a base64 unsigned legacy transaction with the caller as fee
  payer. It is simulated before it's returned, so a doomed transaction comes
  back as a 400 with the program's reason.
- Errors are `{ "error": "…" }`; invalid bodies (zod) return `{ error, issues }`.

## Indexer

The indexer calls `getProgramAccounts` for `Listing` + `Escrow` every
`INDEX_INTERVAL_MS` (15 s). It also subscribes to program logs: each event is
recorded at `/events` and triggers a debounced re-index, so new listings show up
within about 1 s. Settled or cancelled listings are closed accounts, so they
drop out by themselves. There is no database: a restart rebuilds the index from
chain.

The public devnet RPC rate-limits (HTTP 429; web3.js retries automatically).
For demos, point `RPC_URL` at a dedicated devnet endpoint.
