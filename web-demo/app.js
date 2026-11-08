/* TapTrade simulator: two phones, one simulated chain. Mirrors the Android app's screens and
   its on-phone release checks. Nothing here touches a network or a wallet. */
(() => {
  "use strict";

  const SELLER = "6aMZ8C4DZvgbmtDtvL75MHF84W4Dyx1rm9u2PLJtRA4y";
  const BUYER = "8884mmGFfrXAcdFrTu2ispkB3MgQFfbQ1Jb1bAiyhUNv";
  const PROGRAM = "TapTrade";
  const HOST = "api.taptrade.app";
  const SENTENCE = "sell my calc textbook for 20 USDC by Friday";
  const short = (a) => (a.length > 10 ? `${a.slice(0, 4)}…${a.slice(-4)}` : a);
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
  const fakeKey = () => Array.from({ length: 44 }, () => B58[Math.floor(Math.random() * B58.length)]).join("");
  const nonce = () => Array.from({ length: 32 }, () => "0123456789abcdef"[Math.floor(Math.random() * 16)]).join("");
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

  function friday() {
    const d = new Date();
    d.setDate(d.getDate() + ((5 - d.getDay() + 7) % 7 || 7));
    return d.toLocaleDateString("en-US", { weekday: "short", month: "short", day: "numeric" });
  }
  function gradient(title) {
    const pairs = [["#3b82f6", "#a855f7"], ["#14f195", "#19c3f0"], ["#c026d3", "#fb7185"], ["#f59e0b", "#ef4444"], ["#6366f1", "#14f195"]];
    let h = 0;
    for (const ch of title) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
    const [a, b] = pairs[h % pairs.length];
    return `linear-gradient(135deg, ${a}, ${b})`;
  }

  /* ---------- simulated chain + app state ---------- */
  let S;
  function fresh() {
    return {
      listings: [],            // {id,title,price,cat,due,status:'open'|'funded'|'closed',escrow,vault,buyer}
      active: null,            // listing id in the main trade
      rep: { [SELLER]: 15, [BUYER]: 15 },
      seller: { screen: "listings", dialog: null, typed: "", wallet: null, toast: null, busy: null, published: null },
      buyer: { screen: "listings", showFormula: false, wallet: null, toast: null, busy: null, review: null, banner: null },
      phase: "start",          // drives "Next step" and the guide
      auto: false,
      lock: false,
    };
  }
  const L = (id) => S.listings.find((l) => l.id === id);

  /* ---------- the release checks (same rules as the app's ReleaseChecks) ---------- */
  function buildReleaseTx(listing, { tampered }) {
    const ix = [{ program: PROGRAM, name: "release", accounts: { escrow: listing.escrow, vault: listing.vault, seller: SELLER, mint: "tUSDC" }, amount: listing.price }];
    if (tampered) ix.push({ program: "SPL Token", name: "Approve", note: "lets someone else spend your tokens" });
    return { feePayer: BUYER, signers: [BUYER], instructions: ix };
  }
  function checkRelease(link, listing, tx) {
    const url = new URL(link.replace(/^solana:/, ""));
    const escrowParam = url.searchParams.get("escrow");
    const linkOk = url.host === HOST;
    const out = [{ ok: linkOk, name: "Link comes from TapTrade", fail: "Link comes from another server", detail: linkOk ? `Host ${url.host}` : `Host ${url.host} is not TapTrade` }];
    if (!linkOk) return out; // blocked before contacting the link
    out.push({ ok: escrowParam === listing.escrow, name: "Tap is for this listing's escrow", fail: "Tap is for a different escrow", detail: `Escrow ${short(escrowParam)}` });
    out.push({ ok: listing.status === "funded", name: "Escrow is live on Solana", fail: "Escrow isn't funded", detail: `Holds ${listing.price} tUSDC for this listing` });
    out.push({ ok: listing.buyer === BUYER, name: "You funded this escrow", fail: "Someone else funded this escrow", detail: `Buyer on-chain: ${short(BUYER)}` });
    const signersOk = tx.signers.length === 1 && tx.signers[0] === BUYER;
    out.push({ ok: signersOk, name: "Only your wallet signs", fail: "Other signers are required", detail: `${tx.signers.length} signer(s); fee payer ${short(tx.feePayer)}` });
    const foreign = tx.instructions.filter((i) => i.program !== PROGRAM);
    out.push({ ok: foreign.length === 0, name: "Calls only the TapTrade program", fail: "Calls a program it shouldn't",
      detail: foreign.length ? foreign.map((i) => `${i.program}: ${i.name} (${i.note})`).join("; ") : `${tx.instructions.length} instruction(s), no token approvals or transfers` });
    const rel = tx.instructions.find((i) => i.program === PROGRAM);
    out.push({ ok: !!rel && rel.name === "release", name: "Action is release", fail: "Action isn't release", detail: "release()" });
    const acc = rel && rel.accounts;
    const accOk = !!acc && acc.escrow === listing.escrow && acc.vault === listing.vault && acc.seller === SELLER && acc.mint === "tUSDC";
    out.push({ ok: accOk, name: "Escrow, vault, seller and mint match the chain", fail: "Accounts don't match the chain", detail: `Pays seller ${short(SELLER)} from vault ${short(listing.vault)}` });
    out.push({ ok: !!rel && rel.amount === listing.price, name: `Amount is ${listing.price} tUSDC`, fail: "Amount doesn't match", detail: `Escrow and vault both hold ${listing.price} tUSDC` });
    return out;
  }

  /* ---------- rendering ---------- */
  const $ = (id) => document.getElementById(id);
  const statusbar = () => `<div class="statusbar"><span>${new Date().toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })}</span><span>devnet</span></div>`;
  const addr = (who) => `<span class="chip-addr">${short(who)}</span>`;
  const avatar = (t) => `<div class="avatar" style="background:${gradient(t)}">${esc(t[0])}</div>`;
  const tick = `<svg viewBox="0 0 100 100" aria-hidden="true"><circle cx="50" cy="50" r="46" fill="#0f2e22" stroke="#14f195" stroke-width="4"/><path d="M30 52 L44 66 L71 37" fill="none" stroke="#14f195" stroke-width="9" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
  const cross = `<svg viewBox="0 0 100 100" aria-hidden="true"><circle cx="50" cy="50" r="46" fill="#3a1720" stroke="#ff5c7a" stroke-width="4"/><path d="M34 34 L66 66 M66 34 L34 66" fill="none" stroke="#ff5c7a" stroke-width="8" stroke-linecap="round"/></svg>`;

  function listingCard(l, me) {
    const pill = l.status === "open" ? `<span class="pill open">Open</span>` : `<span class="pill escrow">In escrow</span>`;
    return `<button class="card listing" data-act="open" data-id="${l.id}"><div class="row">${avatar(l.title)}
      <div class="grow"><div style="font-weight:700">${esc(l.title)}</div><div class="sub">${me === SELLER ? "Your listing" : "Seller " + short(SELLER)}</div><div style="margin-top:4px">${pill}</div></div>
      <div class="price">${l.price}<small>tUSDC</small></div></div></button>`;
  }
  function listingsScreen(me) {
    const open = S.listings.filter((l) => l.status !== "closed");
    return `<div class="topbar"><div class="title"><span>Tap</span><span class="tap-g">Trade</span></div>${addr(me)}</div>
      <div class="screen"><div><h2>On campus now</h2><p class="sub">Paid through a Solana escrow, released with a tap</p></div>
      ${me === SELLER ? `<button class="cta" data-act="sell">+  Sell something</button>` : ""}
      ${open.length ? open.map((l) => listingCard(l, me)).join("") : `<div class="empty">No open listings on-chain yet.</div>`}</div>`;
  }
  function repCard(title, wallet) {
    const c = S.rep[wallet], counted = Math.min(c, 3), score = Math.round((100 * (counted + 1)) / (counted + 2));
    return `<div class="card"><div class="label">${title}</div>
      <div class="row"><div class="score grow">${score} <small>/ 100</small></div><span class="pill code">Building</span></div>
      <div class="bar"><i style="width:${score}%"></i></div>
      <div style="font-size:13.5px">${c} completed · 0 refunded · 0 disputed · 1 trader</div>
      <button class="link" data-act="formula">${S.buyer.showFormula ? "Hide how it's scored" : "How is this scored?"}</button>
      ${S.buyer.showFormula ? `<div class="formula">score = 100 × (C + 1) / (C + refunded + 2×disputed + 2), where C = completed trades counting at most 3 per counterparty. A new wallet starts at 50. Counted here: ${counted} of ${c}.</div>` : ""}</div>`;
  }
  function header(l, sub) {
    return `<div class="card"><div class="row">${avatar(l.title)}<div class="grow"><div style="font-weight:700">${esc(l.title)}</div><div class="sub">${sub}</div></div><div class="price" style="font-size:20px">${l.price} tUSDC</div></div></div>`;
  }

  function renderSeller() {
    const st = S.seller;
    let body = "";
    if (st.screen === "listings") body = listingsScreen(SELLER);
    if (st.screen === "handoff" || st.screen === "paid") {
      const l = L(st.handoffId);
      const top = `<div class="topbar"><button class="back" data-act="back">Back</button><div class="title">Handoff · seller</div>${addr(SELLER)}</div>`;
      if (st.screen === "paid") {
        body = `${top}<div class="screen">${header(l, "Paid · settled on Solana")}
          <div class="hero ok">${tick}<div class="word">Paid</div><div>The tUSDC is in your wallet</div></div>
          <div class="banner">Your phone saw the escrow close straight from Solana, not from our server.</div>
          ${repCard("Your reputation (updated on-chain)", SELLER)}</div>`;
      } else {
        body = `${top}<div class="screen">${header(l, "in escrow on Solana")}
          <div class="card"><div class="row"><h2 class="grow" style="margin:0;font-size:19px">Ready for handoff</h2><span class="pill live">● Live on-chain</span></div>
          <p class="sub" style="font-size:13px">Hand over the item, then let the buyer tap your phone. Their wallet releases the escrow to you, and this screen flips to Paid by itself.</p>
          ${st.published ? `<span class="pill code" style="align-self:flex-start">One-time handoff code ready · valid 2 min</span>` : ""}</div>
          ${st.published ? `<button class="cta tonal" disabled>✓ Published · waiting for the buyer's tap</button>` : `<button class="cta" data-act="publish">Publish for tap</button>`}
          ${repCard("Buyer's reputation", BUYER)}</div>`;
      }
    }
    if (st.dialog === "describe") {
      body += `<div class="dialog-back"><div class="dialog"><h3>New listing</h3><div class="label">Describe it</div>
        <div class="textarea ${st.typed.length < SENTENCE.length ? "caret" : ""}">${esc(st.typed)}</div>
        <div class="actions"><button class="textbtn" data-act="cancelSell">Cancel</button><button class="cta" data-act="draft" ${st.typed.length < SENTENCE.length ? "disabled" : ""}>Draft listing</button></div></div></div>`;
    }
    if (st.dialog === "review") {
      body += `<div class="dialog-back"><div class="dialog"><h3>Review your listing</h3>
        <div class="inner"><div class="row">${avatar("Calc textbook")}<div style="font-weight:700;font-size:19px">Calc textbook</div></div>
        <div class="big">20 tUSDC</div><div>Hand off by ${friday()} · textbooks</div><span class="pill live" style="align-self:flex-start">Read from your sentence · check it</span></div>
        <p class="sub" style="font-size:13px">Title and price go on-chain. Your phone checks the transaction matches this before your wallet opens.</p>
        <div class="actions"><button class="textbtn" data-act="cancelSell">Edit</button><button class="cta" data-act="signList">Sign &amp; list</button></div></div></div>`;
    }
    $("seller").innerHTML = statusbar() + `<div style="flex:1;display:flex;flex-direction:column;min-height:0">${body}</div>` + overlays(st, "seller");
  }

  function renderBuyer() {
    const st = S.buyer;
    let body = "";
    if (st.screen === "listings") body = listingsScreen(BUYER);
    else {
      const l = L(st.listingId);
      const title = st.screen === "detail" ? "Listing" : "Handoff · buyer";
      const top = `<div class="topbar"><button class="back" data-act="back">Back</button><div class="title">${title}</div>${addr(BUYER)}</div>`;
      if (st.screen === "detail") {
        body = `${top}<div class="screen">${st.banner ? `<div class="banner">${st.banner}</div>` : ""}
          <div class="card"><div class="row">${avatar(l.title)}<div class="grow"><div style="font-weight:700;font-size:20px">${esc(l.title)}</div>
          <span class="pill ${l.status === "open" ? "open" : "escrow"}">${l.status === "open" ? "Open" : "In escrow"}</span></div></div>
          <div><span class="big" style="font-size:34px">${l.price}</span> <span class="sub" style="font-size:15px">tUSDC</span></div>
          <div class="kv"><span>Seller</span><span>${short(SELLER)}</span></div>
          ${l.escrow && l.status !== "open" ? `<div class="kv"><span>Escrow</span><span>${short(l.escrow)}</span></div>` : ""}</div>
          ${repCard("Seller reputation", SELLER)}
          ${l.status === "open" ? `<button class="cta" data-act="fund">Fund escrow · ${l.price} tUSDC</button><p class="sub">Your tUSDC goes into a program vault, not to the seller, until you tap at the handoff. Refundable after the deadline.</p>`
            : `<button class="cta" data-act="wait">Meet seller · wait for tap</button>`}</div>`;
      }
      if (st.screen === "waiting") {
        body = `${top}<div class="screen">${header(l, "in escrow on Solana")}
          <div class="hero">${`<div class="spinner" style="width:42px;height:42px;border-width:4px;margin:0"></div>`}<div style="font-weight:700;font-size:20px">Waiting for tap…</div>
          <p class="sub" style="font-size:13px">Tap the seller's phone after you've checked the item.</p></div></div>`;
      }
      if (st.screen === "review") {
        const r = st.review, shown = r.checks.slice(0, r.revealed), done = r.revealed >= r.checks.length;
        body = `${top}<div class="screen">${header(l, "in escrow on Solana")}
          <div class="card"><div class="label green">Before you sign</div>
          <div style="font-weight:700;font-size:17px">Releases ${l.price} tUSDC to ${short(SELLER)} for "${esc(l.title)}".</div><div class="sub">Plain summary</div>
          <div class="row" style="border-top:1px solid var(--line);padding-top:10px"><b style="font-weight:500">Checked on your phone against Solana</b>${done ? `<span class="count">${r.checks.length}/${r.checks.length} ✓</span>` : ""}</div>
          <div class="checks">${shown.map((c) => `<div class="check ok"><span class="ic">✓</span><div>${esc(c.name)}<small>${esc(c.detail)}</small></div></div>`).join("")}
          ${!done ? `<div class="check pending"><span class="ic"><span class="spinner" style="width:12px;height:12px;margin:0"></span></span><div>Checking…</div></div>` : ""}</div></div></div>
          ${done ? `<div class="sticky-bottom"><button class="cta" data-act="sign">Sign release · ${l.price} tUSDC</button></div>` : ""}`;
      }
      if (st.screen === "blocked") {
        const r = st.review, bad = r.checks.filter((c) => !c.ok), good = r.checks.filter((c) => c.ok);
        body = `${top}<div class="screen">${header(l, "in escrow on Solana")}
          <div class="hero bad">${cross}<div class="word">Blocked</div><div style="font-weight:500">Don't sign this</div></div>
          <div class="card"><div class="label red">Why</div><div class="checks">${bad.map((c) => `<div class="check bad"><span class="ic">✕</span><div>${esc(c.fail)}<small>${esc(c.detail)}</small></div></div>`).join("")}</div>
          ${r.claim ? `<div class="claim">The link described itself as: ${esc(r.claim)} The transaction does something else.</div>` : ""}
          <div class="sub" style="font-size:13px">No signature possible, and the AI was not consulted.</div>
          ${good.length ? `<div class="label" style="border-top:1px solid var(--line);padding-top:10px">Passed</div><div class="checks">${good.map((c) => `<div class="check ok"><span class="ic">✓</span><div>${esc(c.name)}<small>${esc(c.detail)}</small></div></div>`).join("")}</div>` : ""}</div></div>`;
      }
      if (st.screen === "released") {
        body = `${top}<div class="screen">${header(l, "Released · settled on Solana")}
          <div class="hero ok">${tick}<div class="word">Released</div><div style="font-weight:500">Confirmed on Solana in ${st.confirm} s</div></div>
          <div class="banner">Released ${l.price} tUSDC to ${short(SELLER)} for "${esc(l.title)}".</div>
          ${repCard("Your reputation (updated on-chain)", BUYER)}</div>`;
      }
    }
    $("buyer").innerHTML = statusbar() + `<div style="flex:1;display:flex;flex-direction:column;min-height:0">${body}</div>` + overlays(st, "buyer");
  }

  function overlays(st, side) {
    let o = "";
    if (st.busy) o += `<div class="toast" style="color:var(--fg)"><span><span class="spinner"></span>${esc(st.busy)}</span></div>`;
    else if (st.toast) o += `<div class="toast"><span>${esc(st.toast)}</span></div>`;
    if (st.wallet) o += `<div class="wallet" role="dialog" aria-label="Wallet"><div class="w-title">Wallet · sign transaction</div><div>${esc(st.wallet.what)}</div>
      <div class="w-sub">devnet · fee payer ${short(side === "seller" ? SELLER : BUYER)}</div>
      <div class="w-actions"><button class="no" data-act="walletNo" data-side="${side}">Reject</button><button class="ok" data-act="walletOk" data-side="${side}">Approve</button></div></div>`;
    return o;
  }

  /* ---------- guide ---------- */
  const STEPS = [
    ["list", "Seller lists it in one sentence"],
    ["fund", "Buyer checks reputation, funds escrow"],
    ["handoff", "Seller is ready for the handoff"],
    ["tap", "Tap: buyer's phone checks the release"],
    ["sign", "Buyer signs, seller gets paid"],
    ["attack", "A tampered tap gets blocked"],
  ];
  const PHASE_STEP = { start: 0, listed: 1, funded: 2, published: 3, reviewing: 4, released: 5, attackReady: 5, blocked: 5, reviewing2: 5, done: 6 };
  const NARR = {
    start: `The seller types one sentence. AI drafts the listing, and <b>her phone checks the transaction matches</b> before the wallet opens.`,
    listed: `The listing now lives on Solana. The buyer opens it, reads the seller's <b>on-chain reputation</b> (repeat partners count at most 3 times), and locks 20 tUSDC in a program-owned vault.`,
    funded: `The money sits in the escrow vault, not with the seller and not with us. At the handoff the seller publishes a <b>one-time code</b> and her phone starts watching the escrow on-chain.`,
    published: `Now the tap. Press <b>Tap phones</b> (the real app uses NFC; here it's a button). The buyer's phone decodes the release and checks it against the escrow it reads from Solana.`,
    reviewing: `Every rule passed on the buyer's phone. The AI only writes the sentence; <b>the checks do the protecting</b>. Sign the release.`,
    released: `Released in under a second, and the seller's phone flipped to <b>Paid</b> by itself. Next: try to cheat. A mug trade is already funded.`,
    attackReady: `The seller has published the mug's handoff code. Press <b>Tampered tap</b>: same server, same label, but the transaction hides a token approval.`,
    blocked: `<b>Blocked.</b> The phone found an SPL Token Approve that would let someone else spend the buyer's tokens. No Sign button. Press <b>Tap phones</b> to send the genuine one.`,
    reviewing2: `The genuine tap passes all 9 checks on the buyer's phone. <b>Sign release</b> to finish the mug trade.`,
    done: `That's TapTrade: fast enough for a handoff, cheap enough for a five-dollar trade, and the money moves only on the buyer's signature or the program's own rules.`,
  };
  function renderGuide() {
    const cur = PHASE_STEP[S.phase];
    $("steps").innerHTML = STEPS.map(([, t], i) => `<li class="${i < cur ? "done" : i === cur ? "now" : ""}">${t}</li>`).join("");
    $("narration").innerHTML = NARR[S.phase];
    $("tapGenuine").disabled = S.lock || !(S.phase === "published" || S.phase === "blocked");
    $("tapTamper").disabled = S.lock || S.phase !== "attackReady";
    $("next").disabled = S.lock || S.phase === "done";
    $("next").textContent = S.phase === "done" ? "Done" : "Next step";
  }
  function render() { renderSeller(); renderBuyer(); renderGuide(); }

  /* ---------- actions ---------- */
  function walletApprove(side, what) {
    return new Promise((resolve) => {
      S[side].wallet = { what, resolve };
      render();
      if (S.auto) setTimeout(() => S[side].wallet && settleWallet(side, true), 1100);
    });
  }
  function settleWallet(side, ok) {
    const w = S[side].wallet;
    if (!w) return;
    S[side].wallet = null;
    w.resolve(ok);
  }
  async function confirm(side, label, secs) {
    S[side].busy = label; render();
    await sleep(secs * 1000);
    S[side].busy = null;
  }
  function toast(side, text) {
    S[side].toast = text; render();
    setTimeout(() => { if (S[side].toast === text) { S[side].toast = null; render(); } }, 3500);
  }

  async function sell() {
    S.seller.dialog = "describe"; S.seller.typed = ""; render();
    for (let i = 1; i <= SENTENCE.length; i++) { S.seller.typed = SENTENCE.slice(0, i); renderSeller(); await sleep(S.auto ? 28 : 38); }
    render();
  }
  async function draft() { S.seller.dialog = "review"; render(); }
  async function signList() {
    const ok = await walletApprove("seller", "create_listing · \"Calc textbook\" · 20 tUSDC");
    if (!ok) { S.seller.dialog = null; toast("seller", "You declined in the wallet. Nothing was sent."); return; }
    S.seller.dialog = null;
    await confirm("seller", "Sending to Solana…", 1.2);
    const id = "calc";
    S.listings.unshift({ id, title: "Calc textbook", price: 20, status: "open", escrow: null, vault: null, buyer: null });
    S.active = id; S.phase = "listed";
    toast("seller", "Listed on-chain · confirmed in 1.2 s");
  }
  async function openListing(side, id) {
    if (side === "buyer") { S.buyer.screen = "detail"; S.buyer.listingId = id; S.buyer.banner = null; }
    else { S.seller.screen = "handoff"; S.seller.handoffId = id; }
    render();
  }
  async function fund() {
    const l = L(S.buyer.listingId);
    const ok = await walletApprove("buyer", `fund_escrow · ${l.price} tUSDC into the program vault`);
    if (!ok) { toast("buyer", "You declined in the wallet. Nothing was sent."); return; }
    await confirm("buyer", "Sending to Solana…", 1.1);
    Object.assign(l, { status: "funded", escrow: fakeKey(), vault: fakeKey(), buyer: BUYER });
    S.buyer.banner = "Escrow funded · confirmed in 1.1 s. Meet the seller to hand off.";
    S.phase = "funded"; render();
  }
  async function publish() {
    const l = L(S.seller.handoffId);
    S.seller.published = `solana:https://${HOST}/tx/release?escrow=${l.escrow}&nonce=${nonce()}`;
    if (S.phase === "funded") S.phase = "published";
    render();
  }
  async function wait() { S.buyer.screen = "waiting"; render(); }

  async function tap(kind) {
    const link = kind === "tamper" ? S.seller.published.replace("/tx/release?", "/demo/evil/tx/release?") : S.seller.published;
    const escrowParam = new URL(link.replace(/^solana:/, "")).searchParams.get("escrow");
    const l = S.listings.find((x) => x.escrow === escrowParam);
    S.buyer.screen = "review"; S.buyer.listingId = l.id;
    const tx = buildReleaseTx(l, { tampered: kind === "tamper" });
    const checks = checkRelease(link, l, tx);
    const ok = checks.every((c) => c.ok);
    S.buyer.review = { checks, revealed: 0, link, claim: `Release ${l.price} tUSDC to ${short(SELLER)} for "${l.title}".` };
    if (!ok) { await sleep(500); S.buyer.screen = "blocked"; S.phase = "blocked"; render(); return; }
    render();
    for (let i = 1; i <= checks.length; i++) { await sleep(S.auto ? 170 : 230); S.buyer.review.revealed = i; renderBuyer(); }
    if (S.phase === "published") S.phase = "reviewing";
    else if (S.phase === "blocked") S.phase = "reviewing2";
    render();
  }
  async function sign() {
    const l = L(S.buyer.listingId);
    const ok = await walletApprove("buyer", `release · ${l.price} tUSDC to ${short(SELLER)}`);
    if (!ok) { toast("buyer", "You declined in the wallet. Nothing was sent."); return; }
    await confirm("buyer", "Sending to Solana…", 0.8);
    l.status = "closed";
    S.rep[SELLER]++; S.rep[BUYER]++;
    S.buyer.screen = "released"; S.buyer.confirm = 0.8;
    render();
    await sleep(500);                                   // the seller's websocket sees the escrow close
    if (S.seller.handoffId === l.id) S.seller.screen = "paid";
    S.phase = l.id === "calc" ? "released" : "done";
    render();
  }
  async function prepareAttack() {
    // Pre-staged, as in the live demo: a funded mug trade with the seller on its handoff screen.
    const id = "mug";
    S.listings.unshift({ id, title: "Mug", price: 2, status: "funded", escrow: fakeKey(), vault: fakeKey(), buyer: BUYER });
    S.seller.screen = "handoff"; S.seller.handoffId = id; S.seller.published = null; render();
    await sleep(500);
    await publish();
    S.buyer.screen = "waiting"; S.buyer.listingId = id;
    S.phase = "attackReady"; render();
  }

  async function next() {
    const p = S.phase, st = S.seller, b = S.buyer;
    if (p === "start") {
      if (!st.dialog) return sell();
      if (st.dialog === "describe") return draft();
      return signList();
    }
    if (p === "listed") {
      if (b.screen !== "detail") { await openListing("buyer", "calc"); b.showFormula = true; render(); return; }
      return fund();
    }
    if (p === "funded") {
      if (st.screen !== "handoff") return openListing("seller", "calc");
      return publish();
    }
    if (p === "published") { if (b.screen !== "waiting") return wait(); return tap("genuine"); }
    if (p === "reviewing" || p === "reviewing2") return sign();
    if (p === "released") return prepareAttack();
    if (p === "attackReady") return tap("tamper");
    if (p === "blocked") return tap("genuine");
  }

  async function run(fn, auto = false) {
    if (S.lock) return;
    S.lock = true; S.auto = auto; renderGuide();
    try { await fn(); } finally { S.lock = false; S.auto = false; render(); }
  }

  /* ---------- events ---------- */
  document.addEventListener("click", (e) => {
    const t = e.target.closest("[data-act]");
    if (!t) return;
    const act = t.dataset.act, side = t.closest("#seller") ? "seller" : "buyer";
    if (act === "walletOk" || act === "walletNo") { settleWallet(t.dataset.side, act === "walletOk"); return; }
    if (act === "formula") { S.buyer.showFormula = !S.buyer.showFormula; render(); return; }
    const map = {
      sell: () => sell(), draft: () => draft(), signList: () => signList(),
      cancelSell: () => { S.seller.dialog = null; render(); },
      open: () => openListing(side, t.dataset.id),
      fund: () => fund(), wait: () => wait(), publish: () => publish(), sign: () => sign(),
      back: () => { S[side].screen = "listings"; render(); },
    };
    if (map[act]) run(map[act]);
  });
  $("next").addEventListener("click", () => run(next, true));
  $("reset").addEventListener("click", () => { S = fresh(); render(); });
  $("tapGenuine").addEventListener("click", () => run(async () => { if (S.buyer.screen !== "waiting" && S.phase === "published") await wait(); await tap("genuine"); }));
  $("tapTamper").addEventListener("click", () => run(() => tap("tamper")));

  S = fresh();
  render();
  // ?steps=N plays N "Next step" presses on load (handy for linking to a moment in the demo).
  const steps = Math.min(40, parseInt(new URLSearchParams(location.search).get("steps") || "0", 10) || 0);
  (async () => { for (let i = 0; i < steps && S.phase !== "done"; i++) await run(next, true); })();
  setInterval(() => { if (!S.lock) { document.querySelectorAll(".statusbar span:first-child").forEach((n) => (n.textContent = new Date().toLocaleTimeString([], { hour: "numeric", minute: "2-digit" }))); } }, 30000);
})();
