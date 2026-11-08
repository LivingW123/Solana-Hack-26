package app.taptrade.ui.handoff

import android.net.Uri
import android.util.Base64
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.taptrade.BuildConfig
import app.taptrade.data.AccountEvent
import app.taptrade.data.BackendApi
import app.taptrade.data.Listing
import app.taptrade.data.Reputation
import app.taptrade.data.SolanaRpc
import app.taptrade.explain.ChainState
import app.taptrade.explain.Check
import app.taptrade.explain.EscrowAccount
import app.taptrade.explain.ReleaseChecks
import app.taptrade.explain.ReleaseContext
import app.taptrade.tap.DeepLinkTapTransport
import app.taptrade.tap.TapTransport
import app.taptrade.wallet.WalletManager
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class HandoffRole { Seller, Buyer }

/** How the escrow closed, as read from chain: `release` closes the listing too; a refund reopens it. */
enum class Settlement { Paid, Refunded }

/**
 * A tapped `solana:<link>` Solana Pay transaction request, parsed.
 * [link] is the https/http URL to POST to (null if the URL isn't one).
 */
data class ReleaseRequest(val url: String, val link: String?, val escrow: String?, val nonce: String?)

data class Released(val signature: String, val confirmMs: Long, val message: String?)

/** Explainer result for one tapped release. Only `ok` reviews carry signable [tx] bytes. */
data class Review(
    val checks: List<Check>,
    val ok: Boolean,
    /** The label the link's server attached ("Release 20 tUSDC…"). Untrusted; shown for contrast. */
    val claimed: String? = null,
    val tx: ByteArray? = null,
    val txBase64: String? = null,
    val fallbackSummary: String? = null,
    /** One sentence; null while it loads (ok reviews only). */
    val summary: String? = null,
    /** "llm", "fallback" (backend template) or "local" (backend unreachable). */
    val summarySource: String? = null,
    /** When [tx] was fetched; its blockhash is only good for ~60–90 s. */
    val fetchedAt: Long = 0L,
)

data class HandoffUiState(
    val loading: Boolean = true,
    val listing: Listing? = null,
    val connecting: Boolean = false,
    /** The other side's on-chain reputation (seller sees buyer's, buyer sees seller's). */
    val counterpartRep: Reputation? = null,
    /** Own reputation, re-read after the trade settles (counters bumped on-chain by `release`). */
    val myRep: Reputation? = null,
    // Seller
    val url: String? = null,
    val expiresAt: Long? = null,
    val published: Boolean = false,
    /** Websocket subscription to the escrow account is live. */
    val watching: Boolean = false,
    val settlement: Settlement? = null,
    // Buyer
    val received: ReleaseRequest? = null,
    val reviewing: Boolean = false,
    val review: Review? = null,
    val releasing: Boolean = false,
    val released: Released? = null,
    val error: String? = null,
)

class HandoffViewModel(
    private val backend: BackendApi,
    private val wallet: WalletManager,
    private val rpc: SolanaRpc,
    private val tap: TapTransport,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val pubkey: String = checkNotNull(savedState["pubkey"])
    val role: HandoffRole = HandoffRole.valueOf(checkNotNull(savedState["role"]))

    /** In-app "simulate tap" is only possible with the debug transport. */
    val canSimulateTap: Boolean = tap is DeepLinkTapTransport

    private val _state = MutableStateFlow(HandoffUiState())
    val state: StateFlow<HandoffUiState> = _state.asStateFlow()
    val address: StateFlow<String?> = wallet.address

    private var watchJob: Job? = null

    private val onTap: (String) -> Unit = { url ->
        _state.update { it.copy(received = parseReleaseRequest(url), review = null, released = null, error = null) }
        maybeReview()
    }

    init {
        // A tap held by the transport (deep link that opened this screen) is delivered right here.
        if (role == HandoffRole.Buyer) tap.listen(onTap)
        load()
    }

    private fun load() {
        viewModelScope.launch {
            runCatching {
                val listing = backend.listing(pubkey)
                val ticket = if (role == HandoffRole.Seller) {
                    val escrow = checkNotNull(listing.escrow) { "Listing is not funded" }
                    val me = checkNotNull(wallet.address.value) { "Connect a wallet first" }
                    backend.handoff(escrow, me)
                } else {
                    null
                }
                listing to ticket
            }.onSuccess { (listing, ticket) ->
                _state.update {
                    it.copy(loading = false, listing = listing, url = ticket?.url, expiresAt = ticket?.expiresAt)
                }
                if (role == HandoffRole.Seller) listing.escrow?.let { watchEscrow(it, listing.pubkey) }
                maybeReview()
                val other = if (role == HandoffRole.Seller) listing.buyer else listing.seller
                other?.let { o ->
                    runCatching { backend.reputation(o) }.getOrNull()?.let { r -> _state.update { it.copy(counterpartRep = r) } }
                }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    /**
     * Wallet sessions live in memory, so a tap that cold-starts the app lands here
     * disconnected. Connect in place; the held tap stays on screen (its nonce is only
     * spent when the release tx is requested).
     */
    fun connect(sender: ActivityResultSender) {
        viewModelScope.launch {
            _state.update { it.copy(connecting = true, error = null) }
            val r = wallet.connect(sender)
            _state.update { it.copy(connecting = false, error = r.exceptionOrNull()?.message) }
            // Seller needs the address to get a handoff URL.
            if (r.isSuccess && role == HandoffRole.Seller && _state.value.url == null) regenerate()
            if (r.isSuccess) maybeReview()
        }
    }

    // ------------------------------------------------------------------ seller

    /** Seller: hand the URL to the tap transport. */
    fun publish() {
        val url = _state.value.url ?: return
        tap.publish(url)
        _state.update { it.copy(published = true) }
    }

    /** Seller: new nonce (they expire after 2 minutes). */
    fun regenerate() {
        _state.update { it.copy(loading = true, url = null, published = false, error = null) }
        load()
    }

    /**
     * Seller's "Paid ✓" comes from the chain, not the backend: subscribe to the escrow
     * account over the RPC websocket and wait for it to close. Reconnects if the socket
     * drops; after each (re)subscribe, checks once in case it closed in between.
     */
    private fun watchEscrow(escrow: String, listing: String) {
        if (watchJob != null) return
        watchJob = viewModelScope.launch {
            while (isActive && _state.value.settlement == null) {
                try {
                    rpc.watchAccount(escrow)
                        .map { ev ->
                            when (ev) {
                                AccountEvent.Subscribed -> {
                                    _state.update { it.copy(watching = true) }
                                    !rpc.accountExists(escrow)
                                }
                                is AccountEvent.Changed -> !ev.exists
                            }
                        }
                        .first { closed -> closed }
                    // Escrow closed. `release` closes the listing as well; `refund_after_deadline` reopens it.
                    val settlement = if (rpc.accountExists(listing)) Settlement.Refunded else Settlement.Paid
                    _state.update { it.copy(settlement = settlement, watching = false) }
                    refreshMyRep()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(watching = false) }
                    delay(2_000)
                }
            }
        }
    }

    // ------------------------------------------------------------------ buyer

    /** Buyer (debug): pretend a tap delivered this URL. */
    fun simulateTap(url: String) {
        (tap as? DeepLinkTapTransport)?.deliver(url.trim())
    }

    override fun onCleared() {
        if (role == HandoffRole.Buyer) tap.stopListening(onTap)
    }

    /** Start the explainer as soon as we have a tap, the listing and a wallet. */
    private fun maybeReview() {
        val s = _state.value
        if (role != HandoffRole.Buyer || s.received == null || s.listing == null || s.review != null || s.reviewing) return
        if (wallet.address.value == null) return
        review()
    }

    /**
     * Explainer. Rule checks run HERE, on the phone, against the Escrow account fetched
     * straight from RPC; any failure blocks signing and the LLM is not consulted.
     *  1. Link checks before our address goes anywhere (host allowlist, escrow = listing's).
     *  2. Fetch the unsigned tx (Solana Pay POST), decode it, compare with the chain.
     *  3. Only if every rule passes: ask the backend for the one-sentence summary
     *     (5 s budget, local template on any failure). Signing never waits on it.
     */
    private fun reviewContext(): ReleaseContext? {
        val s = _state.value
        val req = s.received ?: return null
        val listing = s.listing ?: return null
        val me = wallet.address.value ?: return null
        return ReleaseContext(
            programId = BuildConfig.PROGRAM_ID,
            trustedBackendUrl = BuildConfig.BACKEND_URL,
            me = me,
            listing = listing.pubkey,
            listingTitle = listing.title,
            listingPrice = listing.price,
            listingEscrow = listing.escrow,
            link = req.link,
            urlEscrow = req.escrow,
        )
    }

    /** Link checks, then (only if they pass) fetch the tx and check it against the chain. */
    private suspend fun runReview(ctx: ReleaseContext): Review {
        val linkChecks = ReleaseChecks.checkLink(ctx)
        // Blocked before contacting the link: our address never leaves the phone.
        if (linkChecks.any { !it.ok }) return Review(linkChecks, ok = false)

        val tx = backend.transactionRequest(ctx.link!!, ctx.me)
        val bytes = Base64.decode(tx.transaction, Base64.DEFAULT)
        val account = rpc.getAccount(ctx.listingEscrow!!)
        val escrow = account?.data?.let(EscrowAccount::decode)
        val chain = ChainState(account?.owner, account?.data, escrow?.let { rpc.tokenBalance(it.vault) })
        val checks = linkChecks + ReleaseChecks.checkTx(ctx, bytes, chain)
        val ok = checks.all { it.ok }
        return Review(
            checks = checks,
            ok = ok,
            claimed = tx.message,
            tx = if (ok) bytes else null,
            txBase64 = tx.transaction,
            fallbackSummary = ReleaseChecks.fallbackSummary(ctx, chain),
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private fun review() {
        val ctx = reviewContext() ?: return
        viewModelScope.launch {
            _state.update { it.copy(reviewing = true, error = null) }
            runCatching { runReview(ctx) }
                .onSuccess { r ->
                    _state.update { it.copy(reviewing = false, review = r) }
                    if (r.ok) summarize(r)
                }
                .onFailure { e ->
                    _state.update { it.copy(reviewing = false, error = e.message ?: e.toString()) }
                }
        }
    }

    private suspend fun summarize(r: Review) {
        val ex = withTimeoutOrNull(5_000) { runCatching { backend.explain(r.txBase64!!, r.checks) }.getOrNull() }
        _state.update {
            it.copy(review = it.review?.copy(
                summary = ex?.summary ?: r.fallbackSummary,
                summarySource = ex?.source ?: "local",
            ))
        }
    }

    /** Re-run the explainer (e.g. after a network error or a new code from the seller). */
    fun retryReview() {
        _state.update { it.copy(review = null, error = null) }
        maybeReview()
    }

    /**
     * Buyer: sign exactly bytes that passed review → confirm on RPC.
     * A release carries a recent blockhash (~60–90 s on devnet). If the buyer spent a
     * while reading the explainer, fetch a fresh tx for the same code and re-run every
     * check first; sign only if they all still pass.
     */
    fun sign(sender: ActivityResultSender) {
        var r = _state.value.review ?: return
        if (r.tx == null) return // blocked reviews have no signable bytes
        viewModelScope.launch {
            _state.update { it.copy(releasing = true, error = null) }
            runCatching {
                if (System.currentTimeMillis() - r.fetchedAt > REVIEW_FRESH_MS) {
                    val ctx = checkNotNull(reviewContext()) { "Connect a wallet first" }
                    val fresh = runReview(ctx)
                    // Keep the sentence already shown; the facts it describes were re-checked.
                    r = fresh.copy(summary = r.summary, summarySource = r.summarySource)
                    _state.update { it.copy(review = r) }
                    if (!r.ok) return@runCatching null
                }
                val sig = wallet.signAndSend(sender, r.tx!!).getOrThrow()
                val t0 = System.currentTimeMillis()
                rpc.awaitConfirmed(sig)
                Released(sig, System.currentTimeMillis() - t0, (r.summary ?: r.claimed)?.replaceFirst(Regex("^Releases? "), "Released "))
            }.onSuccess { done ->
                _state.update { it.copy(releasing = false, released = done) }
                if (done != null) refreshMyRep()
            }.onFailure { e ->
                if (isExpiry(e)) {
                    // Never a dead end: say what happened and re-check with a fresh transaction.
                    _state.update { it.copy(releasing = false, review = null, error = "That request expired before it was sent. Checking again…") }
                    maybeReview()
                } else {
                    // A dead handoff code invalidates the review: hide the ✓ card and its Sign button
                    // so only the message and "Check release" (after the seller's new tap) remain.
                    val codeGone = "handoff code" in (e.message ?: "").lowercase()
                    _state.update { it.copy(releasing = false, error = friendly(e), review = if (codeGone) null else it.review) }
                }
            }
        }
    }

    /** After settle: re-read own counters (the backend's RPC may lag ours by a moment). */
    private fun refreshMyRep() {
        val me = wallet.address.value ?: return
        viewModelScope.launch {
            delay(1_500)
            runCatching { backend.reputation(me) }.getOrNull()?.let { r -> _state.update { it.copy(myRep = r) } }
        }
    }

    private fun isExpiry(e: Throwable): Boolean {
        val m = (e.message ?: e.toString()).lowercase()
        return "blockhash" in m || "not submitted" in m || "-4/" in m
    }

    private fun friendly(e: Throwable): String {
        val m = e.message ?: e.toString()
        return when {
            "declined" in m.lowercase() || "-3/" in m -> "You declined in the wallet. Nothing was sent."
            "expired" in m.lowercase() && "code" in m.lowercase() -> m
            else -> m.substringAfterLast("RemoteException: ").ifBlank { m }
        }
    }

    private companion object {
        /** Re-fetch the release before signing if the reviewed one is older than this. */
        const val REVIEW_FRESH_MS = 40_000L
    }

}

/** Parse a tapped `solana:<link>`; the spec URL-encodes links that carry a query, so accept both forms. */
fun parseReleaseRequest(url: String): ReleaseRequest {
    var inner = url.trim().removePrefix("solana:")
    if (inner.startsWith("http%3A", ignoreCase = true) || inner.startsWith("https%3A", ignoreCase = true)) {
        inner = Uri.decode(inner)
    }
    val u = Uri.parse(inner)
    val link = inner.takeIf { u.scheme == "https" || u.scheme == "http" }
    return ReleaseRequest(url, link, u.getQueryParameter("escrow"), u.getQueryParameter("nonce"))
}
