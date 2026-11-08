/**
 * End-to-end trade on devnet through the backend's HTTP API, signing locally with
 * the script test wallets (scripts/.wallets/{seller,buyer}-keypair.json) the way a
 * phone's wallet would. Requires the backend running (npm run dev).
 *
 *   BASE=http://localhost:3000 npm run e2e
 */
import fs from "node:fs";
import path from "node:path";
import assert from "node:assert/strict";
import { TOKEN_PROGRAM_ID, getAssociatedTokenAddressSync } from "@solana/spl-token";
import { Connection, Keypair, PublicKey, VersionedTransaction } from "@solana/web3.js";
import "../src/config";

const BASE = (process.env.BASE ?? "http://localhost:3000").replace(/\/$/, "");
const conn = new Connection(process.env.RPC_URL ?? "https://api.devnet.solana.com", "confirmed");
const MINT = new PublicKey(process.env.TUSDC_MINT!);
const PRICE = 1_000_000; // 1 tUSDC

const wallet = (name: string) =>
  Keypair.fromSecretKey(
    Uint8Array.from(JSON.parse(fs.readFileSync(path.resolve(__dirname, `../../scripts/.wallets/${name}-keypair.json`), "utf8"))),
  );
const seller = wallet("seller");
const buyer = wallet("buyer");

async function http(method: string, url: string, body?: unknown): Promise<{ status: number; json: any }> {
  const res = await fetch(url.startsWith("http") ? url : BASE + url, {
    method,
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, json: await res.json() };
}

async function ok(method: string, url: string, body?: unknown) {
  const r = await http(method, url, body);
  assert.equal(r.status, 200, `${method} ${url} → ${r.status} ${JSON.stringify(r.json)}`);
  return r.json;
}

/** What the phone does: decode, sign in the wallet, submit, wait for `confirmed`. */
async function signAndSend(b64: string, signer: Keypair): Promise<{ sig: string; ms: number }> {
  const tx = VersionedTransaction.deserialize(Buffer.from(b64, "base64"));
  assert.ok(tx.message.staticAccountKeys[0].equals(signer.publicKey), "fee payer must be the signer");
  tx.sign([signer]);
  const t0 = Date.now();
  const sig = await conn.sendRawTransaction(tx.serialize());
  const { lastValidBlockHeight } = await conn.getLatestBlockhash("confirmed");
  const res = await conn.confirmTransaction(
    { signature: sig, blockhash: tx.message.recentBlockhash, lastValidBlockHeight },
    "confirmed",
  );
  assert.equal(res.value.err, null, `tx ${sig} failed: ${JSON.stringify(res.value.err)}`);
  return { sig, ms: Date.now() - t0 };
}

/** Escrow layout: 8 discriminator + buyer, seller, listing, mint, vault (32 each) + amount, deadline, bump. */
const escrowVault = (data: Buffer) => new PublicKey(data.subarray(8 + 32 * 4, 8 + 32 * 5));

const tokenBalance = async (owner: PublicKey) =>
  BigInt((await conn.getTokenAccountBalance(getAssociatedTokenAddressSync(MINT, owner))).value.amount);

const step = (s: string) => console.log(`\n▸ ${s}`);

async function main() {
  console.log(`backend ${BASE}\nseller  ${seller.publicKey.toBase58()}\nbuyer   ${buyer.publicKey.toBase58()}`);
  const health = await ok("GET", "/health");
  console.log(`health  slot ${health.slot}, ${health.indexedListings} listings indexed`);

  const repBefore = {
    seller: (await ok("GET", `/reputation/${seller.publicKey.toBase58()}`)).completed as number,
    buyer: (await ok("GET", `/reputation/${buyer.publicKey.toBase58()}`)).completed as number,
  };
  const sellerTokBefore = await tokenBalance(seller.publicKey);

  step("bad requests are rejected");
  assert.equal((await http("POST", "/tx/create-listing", { seller: "nope", title: "x", price: 1 })).status, 400);
  assert.equal((await http("POST", "/tx/create-listing", { seller: seller.publicKey.toBase58(), title: "x".repeat(65), price: 1 })).status, 400);
  assert.equal((await http("POST", "/tx/create-listing", { seller: seller.publicKey.toBase58(), title: "x", price: 0 })).status, 400);

  step("seller creates a listing");
  const created = await ok("POST", "/tx/create-listing", {
    seller: seller.publicKey.toBase58(),
    title: "E2E test item",
    price: PRICE,
  });
  console.log(`  ${created.message} → ${created.listing}`);
  console.log(`  confirmed in ${(await signAndSend(created.transaction, seller)).ms} ms`);

  // The indexer re-indexes on the ListingCreated log event; give it a few seconds.
  let indexed: any;
  for (let i = 0; i < 10 && !indexed; i++) {
    indexed = (await ok("GET", "/listings")).find((l: any) => l.pubkey === created.listing);
    if (!indexed) await new Promise((r) => setTimeout(r, 1000));
  }
  assert.ok(indexed, "new listing appears in /listings");
  assert.equal(indexed.title, "E2E test item");
  assert.equal(indexed.status, "Open");
  assert.equal(indexed.price, PRICE);

  step("Actions GET metadata for the fund link");
  const meta = await ok("GET", `/tx/fund?listing=${created.listing}`);
  assert.equal(meta.type, "action");
  assert.equal(meta.title, "E2E test item");
  console.log(`  ${meta.label}`);

  step("seller cannot buy own listing; buyer funds escrow");
  assert.equal((await http("POST", `/tx/fund?listing=${created.listing}`, { account: seller.publicKey.toBase58() })).status, 400);
  const fund = await ok("POST", `/tx/fund?listing=${created.listing}`, { account: buyer.publicKey.toBase58() });
  console.log(`  ${fund.message}`);
  console.log(`  confirmed in ${(await signAndSend(fund.transaction, buyer)).ms} ms`);

  const funded = await ok("GET", `/listings/${created.listing}`);
  assert.equal(funded.status, "Funded");
  assert.equal(funded.buyer, buyer.publicKey.toBase58());
  assert.equal(funded.escrow, fund.escrow);
  assert.equal((await http("POST", `/tx/fund?listing=${created.listing}`, { account: buyer.publicKey.toBase58() })).status, 409);

  step("seller gets a one-time handoff URL");
  assert.equal((await http("POST", "/tx/release/nonce", { escrow: funded.escrow, seller: buyer.publicKey.toBase58() })).status, 403);
  const ticket = await ok("POST", "/tx/release/nonce", { escrow: funded.escrow, seller: seller.publicKey.toBase58() });
  console.log(`  ${ticket.url}`);
  const link = ticket.url.replace(/^solana:/, "");

  step("wrong wallet can't use the URL (and doesn't burn the nonce)");
  assert.equal((await http("POST", link, { account: seller.publicKey.toBase58() })).status, 403);

  step("seller's phone subscribes to the escrow account over websocket");
  const escrowKey = new PublicKey(funded.escrow);
  const closed = new Promise<number>((resolve) => {
    const id = conn.onAccountChange(
      escrowKey,
      (acc) => {
        if (acc.lamports === 0) {
          void conn.removeAccountChangeListener(id);
          resolve(Date.now());
        }
      },
      "confirmed",
    );
  });

  let tampered: string | undefined;
  if (health.demoTamper) {
    step("tampered route (DEMO_TAMPER): same label, hidden SPL Token approve");
    const evil = await ok("POST", link.replace("/tx/release", "/demo/evil/tx/release"), {
      account: buyer.publicKey.toBase58(),
    });
    const etx = VersionedTransaction.deserialize(Buffer.from(evil.transaction, "base64"));
    const programs = etx.message.compiledInstructions.map((ix) => etx.message.staticAccountKeys[ix.programIdIndex].toBase58());
    assert.ok(programs.includes(TOKEN_PROGRAM_ID.toBase58()), "tampered tx carries a Token program instruction");
    console.log(`  programs: ${programs.join(", ")} · label "${evil.message}"`);
    tampered = evil.transaction;
  }

  step("buyer taps → release (claims the nonce; may re-fetch a fresh blockhash)");
  const first = await ok("POST", link, { account: buyer.publicKey.toBase58() });
  assert.equal((await http("POST", link, { account: seller.publicKey.toBase58() })).status, 409, "claimed by the buyer");
  const rel = await ok("POST", link, { account: buyer.publicKey.toBase58() });
  assert.ok(first.transaction && rel.transaction);
  console.log(`  ${rel.message}`);

  step("explainer sentence (/ai/explain) — never consulted when a check failed");
  const exp = await ok("POST", "/ai/explain", { tx_base64: rel.transaction, checks: [{ rule: "all", ok: true }] });
  assert.ok(exp.summary.includes("1"), `summary states the amount: ${exp.summary}`);
  console.log(`  [${exp.source}] ${exp.summary}`);
  const blocked = await ok("POST", "/ai/explain", { tx_base64: rel.transaction, checks: [{ rule: "x", ok: false }] });
  assert.equal(blocked.source, "fallback");
  assert.equal((await http("POST", "/ai/explain", { tx_base64: "nope", checks: [] })).status, 400);

  if (process.env.WRITE_FIXTURE) {
    // Real devnet transactions + the on-chain Escrow, for the phone's rule-check unit test.
    const escrowInfo = (await conn.getAccountInfo(escrowKey, "confirmed"))!;
    const vault = (await conn.getTokenAccountBalance(new PublicKey(escrowVault(escrowInfo.data)))).value.amount;
    const fixture = {
      programId: health.programId,
      backendUrl: "http://10.0.2.2:3000",
      me: buyer.publicKey.toBase58(),
      listing: created.listing,
      price: PRICE,
      escrow: funded.escrow,
      escrowOwner: escrowInfo.owner.toBase58(),
      escrowData: escrowInfo.data.toString("base64"),
      vaultBalance: Number(vault),
      releaseTx: rel.transaction,
      tamperedTx: tampered ?? null,
    };
    const out = path.resolve(__dirname, "../../android/app/src/test/resources/release-fixture.json");
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, JSON.stringify(fixture, null, 2) + "\n");
    console.log(`  wrote ${path.relative(process.cwd(), out)}`);
  }
  const tSend = Date.now();
  const sent = await signAndSend(rel.transaction, buyer);
  console.log(`  buyer: confirmed in ${sent.ms} ms (${sent.sig})`);
  const paidAt = await Promise.race([closed, new Promise<number>((_, rej) => setTimeout(() => rej(new Error("no websocket close")), 30_000))]);
  console.log(`  seller: websocket saw escrow close ${paidAt - tSend} ms after submit → "Paid ✓"`);

  step("can't release twice; accounts are closed; tokens + reputation moved");
  assert.equal((await http("POST", link, { account: buyer.publicKey.toBase58() })).status, 404, "escrow is gone");
  assert.equal(await conn.getAccountInfo(escrowKey), null, "escrow closed");
  assert.equal(await conn.getAccountInfo(new PublicKey(created.listing)), null, "listing closed");
  assert.equal((await http("GET", `/listings/${created.listing}`)).status, 404);
  assert.equal(await tokenBalance(seller.publicKey), sellerTokBefore + BigInt(PRICE));
  const repSeller = await ok("GET", `/reputation/${seller.publicKey.toBase58()}`);
  const repBuyer = await ok("GET", `/reputation/${buyer.publicKey.toBase58()}`);
  assert.equal(repSeller.completed, repBefore.seller + 1);
  assert.equal(repBuyer.completed, repBefore.buyer + 1);
  console.log(`  seller rep: ${repSeller.score}/100 · ${repSeller.summary}`);

  await new Promise((r) => setTimeout(r, 1500));
  const events = (await ok("GET", "/events")).filter((e: any) => [created.listing].includes(e.data.listing)).map((e: any) => e.name);
  console.log(`  indexed events: ${events.reverse().join(" → ") || "(none yet)"}`);

  console.log("\n✓ full trade completed on devnet through the backend");
  process.exit(0);
}

main().catch((e) => {
  console.error("\n✗", e.message ?? e);
  process.exit(1);
});
