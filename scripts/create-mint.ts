// Create the tUSDC test mint (6 decimals) and save its address to backend/.env.
//   npx tsx create-mint.ts [--force]
// Idempotent: if TUSDC_MINT is already set and exists on-chain, it does nothing
// unless --force is passed.
import { createMint, getMint } from "@solana/spl-token";
import { PublicKey } from "@solana/web3.js";
import {
  TUSDC_DECIMALS,
  connection,
  explorer,
  loadPayer,
  main,
  parseArgs,
  upsertEnv,
} from "./lib";

main(async () => {
  const { flags } = parseArgs();
  const payer = loadPayer();
  const existing = process.env.TUSDC_MINT;

  if (existing && flags.force !== "true") {
    try {
      const m = await getMint(connection, new PublicKey(existing));
      console.log(`tUSDC mint already exists: ${existing} (decimals ${m.decimals})`);
      console.log("Pass --force to create a new one.");
      return;
    } catch {
      console.log(`TUSDC_MINT=${existing} not found on-chain — creating a new mint.`);
    }
  }

  const mint = await createMint(
    connection,
    payer,
    payer.publicKey, // mint authority
    null, // no freeze authority
    TUSDC_DECIMALS
  );
  upsertEnv("TUSDC_MINT", mint.toBase58());
  console.log(`Created tUSDC mint: ${mint.toBase58()} (decimals ${TUSDC_DECIMALS})`);
  console.log(`Mint authority: ${payer.publicKey.toBase58()}`);
  console.log(`Saved TUSDC_MINT to backend/.env`);
  console.log(explorer("address", mint.toBase58()));
});
