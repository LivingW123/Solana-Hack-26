// Show SOL and tUSDC balances.
//   npx tsx balances.ts <name|address>...
import { getAccount, getAssociatedTokenAddressSync } from "@solana/spl-token";
import { LAMPORTS_PER_SOL } from "@solana/web3.js";
import {
  TUSDC_DECIMALS,
  connection,
  formatUnits,
  main,
  parseArgs,
  requireMint,
  resolveTarget,
} from "./lib";

main(async () => {
  const { positional } = parseArgs();
  if (positional.length === 0) throw new Error("Usage: balances.ts <name|address>...");
  const mint = requireMint();

  for (const arg of positional) {
    const { label, pubkey } = resolveTarget(arg);
    const sol = (await connection.getBalance(pubkey)) / LAMPORTS_PER_SOL;
    let tusdc = 0n;
    try {
      tusdc = (await getAccount(connection, getAssociatedTokenAddressSync(mint, pubkey))).amount;
    } catch {
      // no token account yet
    }
    console.log(
      `${label.padEnd(8)} ${pubkey.toBase58()}  ${sol} SOL  ${formatUnits(tusdc, TUSDC_DECIMALS)} tUSDC`
    );
  }
});
