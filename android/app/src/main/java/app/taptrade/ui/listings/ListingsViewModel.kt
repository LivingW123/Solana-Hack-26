package app.taptrade.ui.listings

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.taptrade.BuildConfig
import app.taptrade.data.BackendApi
import app.taptrade.data.DraftResponse
import app.taptrade.explain.ListingChecks
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
    val drafting: Boolean = false,
    val draft: DraftResponse? = null,
    /** Signature of the last listing tx, for the explorer link. */
    val lastTx: String? = null,
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

    /** Seller, manual form: unsigned create_listing from backend → sign → confirm → refresh. */
    fun createListing(sender: ActivityResultSender, title: String, price: String, onDone: () -> Unit) {
        val seller = wallet.address.value ?: return _state.update { it.copy(error = "Connect a wallet first") }
        validate(title, price)?.let { msg -> return _state.update { it.copy(error = msg) } }
        submit(sender, onDone) {
            val tx = backend.createListingTx(seller, title.trim(), parseUnits(price)!!)
            tx.transaction to tx.listing
        }
    }

    /** Seller, natural language: one sentence → draft to review (AI, or rules without a key). */
    fun draft(text: String) {
        val seller = wallet.address.value ?: return _state.update { it.copy(error = "Connect a wallet first") }
        viewModelScope.launch {
            _state.update { it.copy(drafting = true, draft = null, error = null) }
            runCatching { backend.draftListing(text.trim(), seller) }
                .onSuccess { d -> _state.update { it.copy(drafting = false, draft = d) } }
                .onFailure { e -> _state.update { it.copy(drafting = false, error = e.message ?: e.toString()) } }
        }
    }

    fun clearDraft() = _state.update { it.copy(draft = null) }

    /** Seller: sign the reviewed draft, after checking its tx lists exactly what was reviewed. */
    fun signDraft(sender: ActivityResultSender, onDone: () -> Unit) {
        val d = _state.value.draft ?: return
        val me = wallet.address.value ?: return _state.update { it.copy(error = "Connect a wallet first") }
        val txB64 = d.transaction ?: return _state.update { it.copy(error = "Draft has no transaction") }
        val bytes = Base64.decode(txB64, Base64.DEFAULT)
        val bad = ListingChecks.check(bytes, BuildConfig.PROGRAM_ID, me, BuildConfig.TUSDC_MINT, d.draft.title, d.draft.price)
            .filter { !it.ok }
        if (bad.isNotEmpty()) {
            return _state.update { it.copy(error = "Not signing: " + bad.joinToString("; ") { c -> "${c.rule} (${c.detail})" }) }
        }
        submit(sender, onDone) { txB64 to d.listing }
    }

    /** Sign + confirm a create_listing, then wait until the backend's index shows it. */
    private fun submit(sender: ActivityResultSender, onDone: () -> Unit, build: suspend () -> Pair<String, String?>) {
        viewModelScope.launch {
            _state.update { it.copy(creating = true, error = null, message = null) }
            runCatching {
                val (txB64, listing) = build()
                val sig = wallet.signAndSend(sender, Base64.decode(txB64, Base64.DEFAULT)).getOrThrow()
                val t0 = System.currentTimeMillis()
                rpc.awaitConfirmed(sig)
                Triple(System.currentTimeMillis() - t0, listing, sig)
            }.onSuccess { (ms, listing, sig) ->
                _state.update {
                    it.copy(creating = false, draft = null, lastTx = sig, message = "Listed on-chain · confirmed in ${"%.1f".format(ms / 1000.0)} s")
                }
                onDone()
                // Confirmed on our RPC; the backend's index can lag a second or two behind,
                // so re-read until the new listing shows up.
                for (attempt in 1..10) {
                    val l = runCatching { backend.listings() }.getOrNull()
                    if (l != null) _state.update { it.copy(listings = l) }
                    if (l != null && (listing == null || l.any { x -> x.pubkey == listing })) break
                    delay(1_000)
                }
                delay(8_000)
                _state.update { it.copy(message = null, lastTx = null) }
            }.onFailure { e ->
                _state.update { it.copy(creating = false, error = e.message ?: e.toString()) }
            }
        }
    }
}
