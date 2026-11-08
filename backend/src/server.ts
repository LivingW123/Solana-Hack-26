import express, { type NextFunction, type Request, type Response } from "express";
import { z, ZodError } from "zod";
import { env } from "./config";
import { connection, programId, tusdcMint } from "./chain";
import { HttpError, parsePubkey } from "./errors";
import { indexer, loadListing } from "./indexer";
import { checkNonce, consumeNonce, issueNonce } from "./nonces";
import { getReputation } from "./reputation";
import { buildCreateListing, buildFund, buildRelease, buildTamperedRelease, listingSummary, loadEscrow } from "./tx";
import { explain } from "./explain";
import { llmEnabled } from "./llm";

// ---------------------------------------------------------------------------
// Request schemas (zod on every body)
// ---------------------------------------------------------------------------

const Pubkey = z.string().min(32).max(44);
/** Integer token base units, as a JSON number or numeric string. */
const BaseUnits = z.union([z.string().regex(/^\d+$/), z.number().int().nonnegative()]).transform((v) => BigInt(v));

/** Solana Pay transaction request / Actions POST body. */
const AccountBody = z.object({ account: Pubkey });
const CreateListingBody = z.object({
  seller: Pubkey,
  title: z.string().min(1).max(200),
  price: BaseUnits,
  description: z.string().max(2000).optional(),
});
const NonceBody = z.object({ escrow: Pubkey, seller: Pubkey });
const ExplainBody = z.object({
  tx_base64: z.string().min(1).max(4000),
  checks: z
    .array(z.object({ rule: z.string().max(200), ok: z.boolean(), detail: z.string().max(500).optional() }))
    .max(30),
});

// ---------------------------------------------------------------------------

const app = express();
app.disable("x-powered-by");
app.use(express.json({ limit: "16kb" }));

// Open CORS + Actions headers so any wallet / Blink client can call the tx endpoints.
const DEVNET = "solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1";
app.use((req, res, next) => {
  res.set({
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET,POST,PUT,OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization, Content-Encoding, Accept-Encoding",
    "Access-Control-Expose-Headers": "X-Action-Version, X-Blockchain-Ids",
    "X-Action-Version": "2.1.3",
    "X-Blockchain-Ids": DEVNET,
  });
  if (req.method === "OPTIONS") return void res.sendStatus(204);
  const t0 = Date.now();
  res.on("finish", () => console.log(`${req.method} ${req.originalUrl} → ${res.statusCode} (${Date.now() - t0} ms)`));
  next();
});

/** Express 4 doesn't catch async errors; route them to the error handler. */
const ah =
  (fn: (req: Request, res: Response) => Promise<unknown>) => (req: Request, res: Response, next: NextFunction) =>
    fn(req, res).catch(next);

/** Base URL the phone used to reach us; goes into Solana Pay links. */
const publicBase = (req: Request) => (env.PUBLIC_URL ?? `${req.protocol}://${req.get("host")}`).replace(/\/$/, "");
const iconUrl = (req: Request) => `${publicBase(req)}/icon.svg`;
const queryParam = (req: Request, name: string) => {
  const v = req.query[name];
  if (typeof v !== "string" || !v) throw new HttpError(400, `Missing query parameter: ${name}`);
  return v;
};

// --- meta -------------------------------------------------------------------

app.get(
  "/health",
  ah(async (_req, res) => {
    res.json({
      ok: true,
      programId: programId.toBase58(),
      mint: tusdcMint.toBase58(),
      rpc: env.RPC_URL,
      slot: await connection.getSlot("confirmed"),
      indexedListings: indexer.size,
      indexedAt: indexer.refreshedAt,
      llm: llmEnabled ? env.LLM_MODEL : null,
      demoTamper: env.DEMO_TAMPER,
    });
  }),
);

app.get("/icon.svg", (_req, res) => {
  res.type("image/svg+xml").send(
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="14" fill="#14F195"/><text x="32" y="42" font-size="28" text-anchor="middle" font-family="sans-serif" font-weight="700" fill="#111">TT</text></svg>`,
  );
});

/** Solana Actions discovery: Blink clients map our fund URLs to the Actions API. */
app.get("/actions.json", (_req, res) => {
  res.json({ rules: [{ pathPattern: "/tx/fund**", apiPath: "/tx/fund**" }] });
});

// --- indexer reads ------------------------------------------------------------

app.get(
  "/listings",
  ah(async (_req, res) => {
    await indexer.fresh(5_000);
    res.json(indexer.all());
  }),
);

app.get(
  "/listings/:pubkey",
  ah(async (req, res) => {
    const view = await loadListing(parsePubkey(req.params.pubkey, "listing"));
    if (!view) throw new HttpError(404, "Listing not found (sold or cancelled)");
    res.json(view);
  }),
);

app.get("/events", (_req, res) => {
  res.json(indexer.events);
});

app.get(
  "/reputation/:wallet",
  ah(async (req, res) => {
    res.json(await getReputation(parsePubkey(req.params.wallet, "wallet")));
  }),
);

// --- create_listing -------------------------------------------------------------

app.post(
  "/tx/create-listing",
  ah(async (req, res) => {
    const body = CreateListingBody.parse(req.body);
    res.json(
      await buildCreateListing({
        seller: parsePubkey(body.seller, "seller"),
        title: body.title,
        price: body.price,
        description: body.description,
      }),
    );
  }),
);

// --- fund_escrow (Solana Pay transaction request + Solana Actions) --------------

app.get(
  "/tx/fund",
  ah(async (req, res) => {
    const listing = parsePubkey(queryParam(req, "listing"), "listing");
    const s = await listingSummary(listing);
    res.json({
      type: "action",
      icon: iconUrl(req),
      title: s.title,
      description: `${s.price} tUSDC, held in an on-chain escrow until you tap the seller's phone at the handoff.`,
      label: `Buy · ${s.price} tUSDC`,
      ...(s.open ? {} : { disabled: true, error: { message: "Already funded" } }),
    });
  }),
);

app.post(
  "/tx/fund",
  ah(async (req, res) => {
    const { account } = AccountBody.parse(req.body);
    const listing = parsePubkey(queryParam(req, "listing"), "listing");
    const r = await buildFund(listing, parsePubkey(account, "account"));
    res.json({ type: "transaction", transaction: r.transaction, message: r.message, escrow: r.escrow });
  }),
);

// --- release (handoff) ------------------------------------------------------------

/** Seller: one-time Solana Pay URL for the buyer's tap. */
app.post(
  "/tx/release/nonce",
  ah(async (req, res) => {
    const body = NonceBody.parse(req.body);
    const escrow = parsePubkey(body.escrow, "escrow");
    const e = await loadEscrow(escrow);
    if (e.seller.toBase58() !== body.seller) throw new HttpError(403, "Not the seller of this escrow");
    const { nonce, expiresAt } = issueNonce(escrow.toBase58());
    res.json({
      url: `solana:${publicBase(req)}/tx/release?escrow=${escrow.toBase58()}&nonce=${nonce}`,
      expiresAt,
    });
  }),
);

app.get("/tx/release", (req, res) => {
  res.json({ label: "TapTrade handoff", icon: iconUrl(req) });
});

app.post(
  "/tx/release",
  ah(async (req, res) => {
    const { account } = AccountBody.parse(req.body);
    const escrow = parsePubkey(queryParam(req, "escrow"), "escrow");
    const nonce = queryParam(req, "nonce");
    checkNonce(nonce, escrow.toBase58());
    // Build (which verifies the caller is the escrow's buyer) before burning the nonce,
    // so a wrong-wallet request can't use up the seller's code.
    const r = await buildRelease(escrow, parsePubkey(account, "account"));
    consumeNonce(nonce);
    res.json(r);
  }),
);

// --- AI ------------------------------------------------------------------------------

/** LLM sentence only. The phone runs the rule checks and decides; this never gates signing. */
app.post(
  "/ai/explain",
  ah(async (req, res) => {
    const body = ExplainBody.parse(req.body);
    res.json(await explain(body.tx_base64, body.checks));
  }),
);

// --- tampered-tap demo (devnet, opt-in) ---------------------------------------------------

if (env.DEMO_TAMPER) {
  console.warn("[demo] DEMO_TAMPER=1: /demo/evil/tx/release serves a release with a hidden token approval");
  app.get("/demo/evil/tx/release", (req, res) => {
    res.json({ label: "TapTrade handoff", icon: iconUrl(req) });
  });
  app.post(
    "/demo/evil/tx/release",
    ah(async (req, res) => {
      const { account } = AccountBody.parse(req.body);
      const escrow = parsePubkey(queryParam(req, "escrow"), "escrow");
      res.json(await buildTamperedRelease(escrow, parsePubkey(account, "account")));
    }),
  );
}

// --- errors -------------------------------------------------------------------------

app.use((_req, _res, next) => next(new HttpError(404, "Not found")));
app.use((err: unknown, _req: Request, res: Response, _next: NextFunction) => {
  if (err instanceof ZodError) {
    return void res.status(400).json({ error: "Invalid request", issues: err.issues });
  }
  if (err instanceof HttpError) return void res.status(err.status).json({ error: err.message });
  if (err instanceof SyntaxError) return void res.status(400).json({ error: "Malformed JSON" });
  console.error(err);
  res.status(500).json({ error: "Internal error" });
});

indexer.start();
app.listen(env.PORT, "0.0.0.0", () => {
  console.log(`TapTrade backend on :${env.PORT} · program ${programId.toBase58()} · mint ${tusdcMint.toBase58()} · ${env.RPC_URL}`);
});
