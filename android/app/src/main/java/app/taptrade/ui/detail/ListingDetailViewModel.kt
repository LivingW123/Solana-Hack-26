package app.taptrade.ui.detail

import android.util.Base64
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.taptrade.data.BackendApi
import app.taptrade.data.Listing
import app.taptrade.data.Reputation
import app.taptrade.wallet.WalletManager
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ListingDetailUiState(
    val loading: Boolean = true,
    val listing: Listing? = null,
    val sellerRep: Reputation? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class ListingDetailViewModel(
    private val backend: BackendApi,
    private val wallet: WalletManager,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val pubkey: String = checkNotNull(savedState["pubkey"])

    private val _state = MutableStateFlow(ListingDetailUiState())
    val state: StateFlow<ListingDetailUiState> = _state.asStateFlow()
    val address: StateFlow<String?> = wallet.address

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                val l = backend.listing(pubkey)
                l to runCatching { backend.reputation(l.seller) }.getOrNull()
            }.onSuccess { (l, rep) ->
                _state.update { it.copy(loading = false, listing = l, sellerRep = rep) }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    /** Buyer: get unsigned fund_escrow tx from backend → sign in wallet → reload. */
    fun fund(sender: ActivityResultSender) {
        val buyer = wallet.address.value ?: return setError("Connect a wallet first")
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            runCatching {
                val tx = backend.fundTx(pubkey, buyer)
                val bytes = Base64.decode(tx.transaction, Base64.DEFAULT)
                wallet.signAndSend(sender, bytes).getOrThrow()
            }.onSuccess { sig ->
                _state.update { it.copy(busy = false, message = "Escrow funded. Tx $sig") }
                load()
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.message ?: e.toString()) }
            }
        }
    }

    private fun setError(msg: String) = _state.update { it.copy(error = msg) }
}
