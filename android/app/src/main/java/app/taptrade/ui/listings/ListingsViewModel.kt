package app.taptrade.ui.listings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.taptrade.data.BackendApi
import app.taptrade.data.Listing
import app.taptrade.wallet.WalletManager
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ListingsUiState(
    val loading: Boolean = true,
    val listings: List<Listing> = emptyList(),
    val connecting: Boolean = false,
    val error: String? = null,
)

class ListingsViewModel(
    private val backend: BackendApi,
    private val wallet: WalletManager,
) : ViewModel() {
    private val _state = MutableStateFlow(ListingsUiState())
    val state: StateFlow<ListingsUiState> = _state.asStateFlow()
    val address: StateFlow<String?> = wallet.address

    init {
        // Reload when the wallet changes ("yours" vs others).
        viewModelScope.launch { wallet.address.collect { refresh() } }
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
}
