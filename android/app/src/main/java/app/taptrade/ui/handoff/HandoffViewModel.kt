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
import app.taptrade.tap.DebugTapTransport
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

enum class HandoffRole { Seller, Buyer }

/** How the escrow closed, as read from chain: `release` closes the listing too; a refund reopens it. */
enum class Settlement { Paid, Refunded }

/**
 * A tapped `solana:<link>` Solana Pay transaction request, parsed.
 * [link] is the https/http URL to POST to (null if the URL isn't one).
 */
data class ReleaseRequest(val url: String, val link: String?, val escrow: String?, val nonce: String?)

data class Released(val signature: String, val confirmMs: Long, val message: String?)

data class HandoffUiState(
    val loading: Boolean = true,
    val listing: Listing? = null,
    // Seller
    val url: String? = null,
    val expiresAt: Long? = null,
    val published: Boolean = false,
    /** Websocket subscription to the escrow account is live. */
    val watching: Boolean = false,
    val settlement: Settlement? = null,
    // Buyer
    val received: ReleaseRequest? = null,
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
    val canSimulateTap: Boolean = tap is DebugTapTransport

    private val _state = MutableStateFlow(HandoffUiState())
    val state: StateFlow<HandoffUiState> = _state.asStateFlow()

    private var watchJob: Job? = null

    init {
        if (role == HandoffRole.Buyer) {
            tap.listen { url -> _state.update { it.copy(received = parse(url), released = null, error = null) } }
        }
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
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
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
        (tap as? DebugTapTransport)?.deliver(url.trim())
    }

    /**
     * Buyer: POST our address to the tapped Solana Pay link → unsigned `release` tx →
     * sign in wallet → confirm on RPC. Phase 6 inserts the explainer's rule checks
     * (decode tx, compare with the on-chain Escrow) before the signature.
     */
    fun release(sender: ActivityResultSender) {
        val req = _state.value.received ?: return
        val buyer = wallet.address.value ?: return _state.update { it.copy(error = "Connect a wallet first") }
        viewModelScope.launch {
            _state.update { it.copy(releasing = true, error = null) }
            runCatching {
                val link = checkNotNull(req.link) { "Not a Solana Pay transaction request" }
                check(req.escrow != null && req.escrow == _state.value.listing?.escrow) {
                    "This tap is for a different escrow than this listing"
                }
                check(Uri.parse(link).authority == Uri.parse(BuildConfig.BACKEND_URL).authority) {
                    "Untrusted host ${Uri.parse(link).authority}"
                }
                val tx = backend.transactionRequest(link, buyer)
                val sig = wallet.signAndSend(sender, Base64.decode(tx.transaction, Base64.DEFAULT)).getOrThrow()
                val t0 = System.currentTimeMillis()
                rpc.awaitConfirmed(sig)
                Released(sig, System.currentTimeMillis() - t0, tx.message)
            }.onSuccess { r ->
                _state.update { it.copy(releasing = false, released = r) }
            }.onFailure { e ->
                _state.update { it.copy(releasing = false, error = e.message ?: e.toString()) }
            }
        }
    }

    private fun parse(url: String): ReleaseRequest {
        // `solana:<link>`; the spec URL-encodes links that carry a query, so accept both forms.
        var inner = url.trim().removePrefix("solana:")
        if (inner.startsWith("http%3A", ignoreCase = true) || inner.startsWith("https%3A", ignoreCase = true)) {
            inner = Uri.decode(inner)
        }
        val u = Uri.parse(inner)
        val link = inner.takeIf { u.scheme == "https" || u.scheme == "http" }
        return ReleaseRequest(url, link, u.getQueryParameter("escrow"), u.getQueryParameter("nonce"))
    }
}
