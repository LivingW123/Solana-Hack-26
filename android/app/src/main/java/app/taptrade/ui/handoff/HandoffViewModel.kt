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
)

data class HandoffUiState(
    val loading: Boolean = true,
    val listing: Listing? = null,
    val connecting: Boolean = false,
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
    private fun review() {
        val s = _state.value
        val req = s.received ?: return
        val listing = s.listing ?: return
        val me = wallet.address.value ?: return
        val ctx = ReleaseContext(
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
        viewModelScope.launch {
            _state.update { it.copy(reviewing = true, error = null) }
            val linkChecks = ReleaseChecks.checkLink(ctx)
            if (linkChecks.any { !it.ok }) {
                // Blocked before contacting the link: our address never leaves the phone.
                _state.update { it.copy(reviewing = false, review = Review(linkChecks, ok = false)) }
                return@launch
            }
            runCatching {
                val tx = backend.transactionRequest(req.link!!, me)
                val bytes = Base64.decode(tx.transaction, Base64.DEFAULT)
                val account = rpc.getAccount(listing.escrow!!)
                val escrow = account?.data?.let(EscrowAccount::decode)
                val chain = ChainState(account?.owner, account?.data, escrow?.let { rpc.tokenBalance(it.vault) })
                val checks = linkChecks + ReleaseChecks.checkTx(ctx, bytes, chain)
                val ok = checks.all { it.ok }
                Review(
                    checks = checks,
                    ok = ok,
                    claimed = tx.message,
                    tx = if (ok) bytes else null,
                    txBase64 = tx.transaction,
                    fallbackSummary = ReleaseChecks.fallbackSummary(ctx, chain),
                )
            }.onSuccess { r ->
                _state.update { it.copy(reviewing = false, review = r) }
                if (r.ok) summarize(r)
            }.onFailure { e ->
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

    /** Buyer: sign exactly the bytes that passed review → confirm on RPC. */
    fun sign(sender: ActivityResultSender) {
        val r = _state.value.review ?: return
        val tx = r.tx ?: return // blocked reviews have no signable bytes
        viewModelScope.launch {
            _state.update { it.copy(releasing = true, error = null) }
            runCatching {
                val sig = wallet.signAndSend(sender, tx).getOrThrow()
                val t0 = System.currentTimeMillis()
                rpc.awaitConfirmed(sig)
                Released(sig, System.currentTimeMillis() - t0, r.summary ?: r.claimed)
            }.onSuccess { done ->
                _state.update { it.copy(releasing = false, released = done) }
            }.onFailure { e ->
                val msg = e.message ?: e.toString()
                _state.update {
                    it.copy(
                        releasing = false,
                        error = if ("blockhash" in msg.lowercase()) "This review expired. Ask the seller for a new code and tap again." else msg,
                    )
                }
            }
        }
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
