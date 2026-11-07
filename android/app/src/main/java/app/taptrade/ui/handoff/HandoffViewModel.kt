package app.taptrade.ui.handoff

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.taptrade.data.BackendApi
import app.taptrade.data.Listing
import app.taptrade.tap.DebugTapTransport
import app.taptrade.tap.TapTransport
import app.taptrade.wallet.WalletManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HandoffRole { Seller, Buyer }

/** A tapped `solana:https://…/tx/release?escrow=…&nonce=…` URL, parsed. */
data class ReleaseRequest(val url: String, val escrow: String?, val nonce: String?)

data class HandoffUiState(
    val loading: Boolean = true,
    val listing: Listing? = null,
    // Seller
    val url: String? = null,
    val expiresAt: Long? = null,
    val published: Boolean = false,
    // Buyer
    val received: ReleaseRequest? = null,
    val error: String? = null,
)

class HandoffViewModel(
    private val backend: BackendApi,
    private val wallet: WalletManager,
    private val tap: TapTransport,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val pubkey: String = checkNotNull(savedState["pubkey"])
    val role: HandoffRole = HandoffRole.valueOf(checkNotNull(savedState["role"]))

    /** In-app "simulate tap" is only possible with the debug transport. */
    val canSimulateTap: Boolean = tap is DebugTapTransport

    private val _state = MutableStateFlow(HandoffUiState())
    val state: StateFlow<HandoffUiState> = _state.asStateFlow()

    init {
        if (role == HandoffRole.Buyer) {
            tap.listen { url -> _state.update { it.copy(received = parse(url)) } }
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
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

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

    /** Buyer (debug): pretend a tap delivered this URL. */
    fun simulateTap(url: String) {
        (tap as? DebugTapTransport)?.deliver(url.trim())
    }

    private fun parse(url: String): ReleaseRequest {
        // `solana:https://host/path?query` → parse the embedded https URL.
        val inner = Uri.parse(url.removePrefix("solana:"))
        return ReleaseRequest(url, inner.getQueryParameter("escrow"), inner.getQueryParameter("nonce"))
    }
}
