// Reset demo wallets to a known state before a run (Phase 9).
//   npx tsx demo-prep.ts [seller] [buyer] [--sol 0.5] [--tusdc 1000]
// Defaults to the emulator fakewallet accounts. Tops each wallet UP to the targets
// (SOL from the faucet or the payer; tUSDC minted by the payer = mint authority),
// then prints balances, open listings and reputation from the backend if it's running.
import { getAccount, getAssociatedTokenAddressSync, getOrCreateAssociatedTokenAccount, mintTo } from "@solana/spl-token";
import { LAMPORTS_PER_SOL, SystemProgram, Transaction, sendAndConfirmTransaction } from "@solana/web3.js";
import {
  TUSDC_DECIMALS,
  connection,
  formatUnits,
  loadPayer,
  main,
  parseArgs,
  parseUnits,
  requireMint,
  resolveTarget,
} from "./lib";

const EMULATOR_SELLER = "6aMZ8C4DZvgbmtDtvL75MHF84W4Dyx1rm9u2PLJtRA4y";
const EMULATOR_BUYER = "8884mmGFfrXAcdFrTu2ispkB3MgQFfbQ1Jb1bAiyhUNv";
const BACKEND = process.env.BACKEND ?? "http://localhost:3000";

main(async () => {
  const { positional, flags } = parseArgs();
  const targets = positional.length ? positional : [EMULATOR_SELLER, EMULATOR_BUYER];
  const solTarget = Number(flags.sol ?? "0.5");
  const tusdcTarget = parseUnits(flags.tusdc ?? "1000", TUSDC_DECIMALS);
  const mint = requireMint();
  const payer = loadPayer();

  for (const arg of targets) {
    const { label, pubkey } = resolveTarget(arg);

    // SOL: top up to target (faucet first, payer transfer if rate-limited).
    const lamports = await connection.getBalance(pubkey);
    const want = Math.round(solTarget * LAMPORTS_PER_SOL);
    if (lamports < want) {
      const need = want - lamports;
      try {
        const sig = await connection.requestAirdrop(pubkey, need);
        await connection.confirmTransaction(sig, "confirmed");
      } catch {
        await sendAndConfirmTransaction(
          connection,
          new Transaction().add(SystemProgram.transfer({ fromPubkey: payer.publicKey, toPubkey: pubkey, lamports: need })),
          [payer],
        );
      }
    }

    // tUSDC: mint the difference up to target.
    const ata = getAssociatedTokenAddressSync(mint, pubkey);
    const have = await getAccount(connection, ata).then((a) => a.amount).catch(() => 0n);
    if (have < tusdcTarget) {
      const acct = await getOrCreateAssociatedTokenAccount(connection, payer, mint, pubkey);
      await mintTo(connection, payer, mint, acct.address, payer, tusdcTarget - have);
    }

    const sol = (await connection.getBalance(pubkey)) / LAMPORTS_PER_SOL;
    const tok = await getAccount(connection, ata).then((a) => a.amount).catch(() => 0n);
    console.log(`${label}: ${sol.toFixed(3)} SOL · ${formatUnits(tok, TUSDC_DECIMALS)} tUSDC`);

    try {
      const rep = (await (await fetch(`${BACKEND}/reputation/${pubkey.toBase58()}`)).json()) as {
        score: number;
        level: string;
        summary: string;
      };
      console.log(`  reputation ${rep.score}/100 (${rep.level}) · ${rep.summary}`);
    } catch {
      /* backend not running: fine */
    }
  }

  try {
    const listings = (await (await fetch(`${BACKEND}/listings`)).json()) as { title: string; status: string; seller: string }[];
    console.log(`\nOpen/funded listings on-chain: ${listings.length}`);
    for (const l of listings) console.log(`  ${l.status.padEnd(6)} "${l.title}" (seller ${l.seller.slice(0, 4)}…)`);
    if (listings.length) console.log("  Sellers can clear Open ones with Cancel listing in the app.");
  } catch {
    console.log(`\n(backend not reachable at ${BACKEND}; skipped listings)`);
  }
});
