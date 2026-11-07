// Shared helpers for dev scripts. Devnet only — never point these at mainnet.
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import * as dotenv from "dotenv";
import { Connection, Keypair, PublicKey } from "@solana/web3.js";

export const ROOT = path.resolve(__dirname, "..");
export const ENV_PATH = path.join(ROOT, "backend", ".env");
export const ENV_EXAMPLE_PATH = path.join(ROOT, "backend", ".env.example");
export const WALLETS_DIR = path.join(__dirname, ".wallets");
export const TUSDC_DECIMALS = 6;

dotenv.config({ path: ENV_PATH });

export const RPC_URL = process.env.RPC_URL ?? "https://api.devnet.solana.com";

if (/mainnet/i.test(RPC_URL)) {
  throw new Error(`Refusing to run against mainnet RPC: ${RPC_URL}`);
}

export const connection = new Connection(RPC_URL, "confirmed");

const expandHome = (p: string) =>
  p.startsWith("~") ? path.join(os.homedir(), p.slice(1)) : p;

export function readKeypair(file: string): Keypair {
  const raw = JSON.parse(fs.readFileSync(expandHome(file), "utf8")) as number[];
  return Keypair.fromSecretKey(Uint8Array.from(raw));
}

/** Payer for fees/rent and authority of the tUSDC mint (the deployer wallet). */
export function loadPayer(): Keypair {
  return readKeypair(process.env.PAYER_KEYPAIR ?? "~/.config/solana/id.json");
}

/** Local test wallet `scripts/.wallets/<name>-keypair.json`, created on first use. */
export function testWallet(name: string): Keypair {
  if (!/^[a-z0-9_-]+$/i.test(name)) throw new Error(`Bad wallet name: ${name}`);
  const file = path.join(WALLETS_DIR, `${name}-keypair.json`);
  if (fs.existsSync(file)) return readKeypair(file);
  const kp = Keypair.generate();
  fs.mkdirSync(WALLETS_DIR, { recursive: true });
  fs.writeFileSync(file, JSON.stringify(Array.from(kp.secretKey)), { mode: 0o600 });
  console.log(`Created test wallet "${name}": ${kp.publicKey.toBase58()}`);
  return kp;
}

/** Accepts a base58 address or a test-wallet name (e.g. "seller", "buyer"). */
export function resolveTarget(arg: string): { label: string; pubkey: PublicKey } {
  try {
    return { label: arg.slice(0, 8), pubkey: new PublicKey(arg) };
  } catch {
    // not an address — treat as a test-wallet name
  }
  return { label: arg, pubkey: testWallet(arg).publicKey };
}

/** Splits argv into positional args and `--flag value` options. */
export function parseArgs(argv = process.argv.slice(2)) {
  const positional: string[] = [];
  const flags: Record<string, string> = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith("--")) {
      const key = a.slice(2);
      const next = argv[i + 1];
      if (next !== undefined && !next.startsWith("--")) {
        flags[key] = next;
        i++;
      } else {
        flags[key] = "true";
      }
    } else {
      positional.push(a);
    }
  }
  return { positional, flags };
}

/** Insert or replace `KEY=value` in backend/.env (created from .env.example if missing). */
export function upsertEnv(key: string, value: string) {
  let text = fs.existsSync(ENV_PATH)
    ? fs.readFileSync(ENV_PATH, "utf8")
    : fs.existsSync(ENV_EXAMPLE_PATH)
      ? fs.readFileSync(ENV_EXAMPLE_PATH, "utf8")
      : "";
  const line = `${key}=${value}`;
  const re = new RegExp(`^${key}=.*$`, "m");
  text = re.test(text) ? text.replace(re, line) : `${text.replace(/\n?$/, "\n")}${line}\n`;
  fs.writeFileSync(ENV_PATH, text);
  process.env[key] = value;
}

export function requireMint(): PublicKey {
  const m = process.env.TUSDC_MINT;
  if (!m) throw new Error("TUSDC_MINT is not set in backend/.env — run `npm run create-mint` first.");
  return new PublicKey(m);
}

/** Base units → display string, e.g. 1000000000n → "1000". UI-only formatting. */
export function formatUnits(amount: bigint, decimals: number): string {
  const s = amount.toString().padStart(decimals + 1, "0");
  const whole = s.slice(0, -decimals);
  const frac = s.slice(-decimals).replace(/0+$/, "");
  return frac ? `${whole}.${frac}` : whole;
}

/** Display string → base units, e.g. "20.5" → 20500000n. Rejects >decimals places. */
export function parseUnits(value: string, decimals: number): bigint {
  const m = /^(\d+)(?:\.(\d+))?$/.exec(value.trim());
  if (!m) throw new Error(`Invalid amount: ${value}`);
  const frac = m[2] ?? "";
  if (frac.length > decimals) throw new Error(`Too many decimals in ${value} (max ${decimals})`);
  return BigInt(m[1]) * 10n ** BigInt(decimals) + BigInt(frac.padEnd(decimals, "0") || "0");
}

export const explorer = (kind: "address" | "tx", id: string) =>
  `https://explorer.solana.com/${kind}/${id}?cluster=devnet`;

export function main(fn: () => Promise<void>) {
  fn().then(
    () => process.exit(0),
    (e) => {
      console.error(e instanceof Error ? e.message : e);
      process.exit(1);
    }
  );
}
