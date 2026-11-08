package app.taptrade.ui.listings

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.taptrade.data.BackendApi
import app.taptrade.data.Listing
import app.taptrade.data.MAX_TITLE_BYTES
import app.taptrade.data.SolanaRpc
import app.taptrade.data.parseUnits
import app.taptrade.wallet.WalletManager
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ListingsUiState(
    val loading: Boolean = true,
    val listings: List<Listing> = emptyList(),
    val connecting: Boolean = false,
    val creating: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class ListingsViewModel(
    private val backend: BackendApi,
    private val wallet: WalletManager,
    private val rpc: SolanaRpc,
) : ViewModel() {
    private val _state = MutableStateFlow(ListingsUiState())
    val state: StateFlow<ListingsUiState> = _state.asStateFlow()
    val address: StateFlow<String?> = wallet.address

    init {
        // Reload when the wallet changes ("yours" vs others). The first load comes from
        // the screen's ON_RESUME, so skip the initial value here.
        viewModelScope.launch { wallet.address.drop(1).collect { refresh() } }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { backend.listings() }
                .onSuccess { l -> _state.update { it.copy(loading = false, listings = l) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: e.toString()) } }
        }
    }

    fun connect(sender: ActivityResultSender) {
        viewModelScope.launch {
            _state.update { it.copy(connecting = true, error = null) }
            val r = wallet.connect(sender)
            _state.update { it.copy(connecting = false, error = r.exceptionOrNull()?.message) }
        }
    }

    fun disconnect() = wallet.disconnect()

    /** Validation shared with the dialog; null = OK. */
    fun validate(title: String, price: String): String? = when {
        title.isBlank() -> "Enter a title"
        title.trim().toByteArray().size > MAX_TITLE_BYTES -> "Title is too long (max $MAX_TITLE_BYTES bytes)"
        parseUnits(price) == null -> "Enter a price in tUSDC, e.g. 20 or 8.5"
        else -> null
    }

    /** Seller: unsigned create_listing from backend → sign in wallet → wait for confirmation → refresh. */
    fun createListing(sender: ActivityResultSender, title: String, price: String, onDone: () -> Unit) {
        val seller = wallet.address.value ?: return _state.update { it.copy(error = "Connect a wallet first") }
        validate(title, price)?.let { msg -> return _state.update { it.copy(error = msg) } }
        viewModelScope.launch {
            _state.update { it.copy(creating = true, error = null, message = null) }
            runCatching {
                val tx = backend.createListingTx(seller, title.trim(), parseUnits(price)!!)
                val sig = wallet.signAndSend(sender, Base64.decode(tx.transaction, Base64.DEFAULT)).getOrThrow()
                val t0 = System.currentTimeMillis()
                rpc.awaitConfirmed(sig)
                System.currentTimeMillis() - t0
            }.onSuccess { ms ->
                _state.update { it.copy(creating = false, message = "Listed on-chain · confirmed in ${"%.1f".format(ms / 1000.0)} s") }
                onDone()
                delay(1_500) // the backend indexer picks the listing up from the ListingCreated event
                refresh()
            }.onFailure { e ->
                _state.update { it.copy(creating = false, error = e.message ?: e.toString()) }
            }
        }
    }
}
