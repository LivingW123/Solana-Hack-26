// Mint tUSDC to test wallets (creates their token accounts if needed).
//   npx tsx mint-tusdc.ts <name|address>... [--amount 1000]
// `--amount` is in whole tUSDC; converted to base units here.
import { getOrCreateAssociatedTokenAccount, mintTo } from "@solana/spl-token";
import {
  TUSDC_DECIMALS,
  connection,
  explorer,
  formatUnits,
  loadPayer,
  main,
  parseArgs,
  parseUnits,
  requireMint,
  resolveTarget,
} from "./lib";

main(async () => {
  const { positional, flags } = parseArgs();
  if (positional.length === 0) {
    throw new Error("Usage: mint-tusdc.ts <name|address>... [--amount 1000]");
  }
  const amount = parseUnits(flags.amount ?? "1000", TUSDC_DECIMALS);
  const mint = requireMint();
  const payer = loadPayer();

  for (const arg of positional) {
    const { label, pubkey } = resolveTarget(arg);
    const ata = await getOrCreateAssociatedTokenAccount(connection, payer, mint, pubkey);
    const sig = await mintTo(connection, payer, mint, ata.address, payer, amount);
    console.log(
      `${label}: minted ${formatUnits(amount, TUSDC_DECIMALS)} tUSDC → ${ata.address.toBase58()}`
    );
    console.log(`  ${explorer("tx", sig)}`);
  }
});
