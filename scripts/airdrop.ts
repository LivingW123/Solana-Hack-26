// Give devnet SOL to test wallets.
//   npx tsx airdrop.ts <name|address>... [--sol 0.5]
// Tries the devnet faucet first; if it's rate-limited, transfers from the payer
// wallet instead. Skips wallets that already have at least the target amount.
import {
  LAMPORTS_PER_SOL,
  SystemProgram,
  Transaction,
  sendAndConfirmTransaction,
} from "@solana/web3.js";
import { connection, explorer, loadPayer, main, parseArgs, parseUnits, resolveTarget } from "./lib";

main(async () => {
  const { positional, flags } = parseArgs();
  if (positional.length === 0) {
    throw new Error("Usage: airdrop.ts <name|address>... [--sol 0.5]");
  }
  const lamports = Number(parseUnits(flags.sol ?? "0.5", 9));
  const payer = loadPayer();

  for (const arg of positional) {
    const { label, pubkey } = resolveTarget(arg);
    const have = await connection.getBalance(pubkey);
    if (have >= lamports) {
      console.log(`${label} (${pubkey.toBase58()}) already has ${have / LAMPORTS_PER_SOL} SOL — skip`);
      continue;
    }
    const need = lamports - have;

    let sig: string | undefined;
    try {
      sig = await connection.requestAirdrop(pubkey, need);
      const bh = await connection.getLatestBlockhash();
      await connection.confirmTransaction({ signature: sig, ...bh }, "confirmed");
      console.log(`${label}: faucet airdrop ${need / LAMPORTS_PER_SOL} SOL`);
    } catch {
      sig = undefined;
    }

    if (!sig) {
      const payerBal = await connection.getBalance(payer.publicKey);
      if (payerBal < need + 10_000) {
        throw new Error(
          `Faucet rate-limited and payer ${payer.publicKey.toBase58()} has only ` +
            `${payerBal / LAMPORTS_PER_SOL} SOL. Fund it at https://faucet.solana.com`
        );
      }
      sig = await sendAndConfirmTransaction(
        connection,
        new Transaction().add(
          SystemProgram.transfer({ fromPubkey: payer.publicKey, toPubkey: pubkey, lamports: need })
        ),
        [payer]
      );
      console.log(`${label}: faucet unavailable, sent ${need / LAMPORTS_PER_SOL} SOL from payer`);
    }
    console.log(`  ${explorer("tx", sig)}`);
  }
});
