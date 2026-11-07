package app.taptrade.wallet

import android.net.Uri
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide wallet session over Mobile Wallet Adapter. The app never holds keys:
 * the wallet app signs, and we only see the public key and signatures.
 */
class WalletManager {
    private val adapter = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            identityUri = Uri.parse("https://taptrade.app"),
            iconUri = Uri.parse("favicon.ico"),
            identityName = "TapTrade",
        ),
    ).apply { blockchain = Solana.Devnet }

    private val _address = MutableStateFlow<String?>(null)

    /** Connected wallet address (base58), or null. */
    val address: StateFlow<String?> = _address.asStateFlow()

    suspend fun connect(sender: ActivityResultSender): Result<String> =
        when (val r = adapter.connect(sender)) {
            is TransactionResult.Success -> {
                val pk = Base58.encode(r.authResult.accounts.first().publicKey)
                _address.value = pk
                Result.success(pk)
            }
            is TransactionResult.NoWalletFound ->
                Result.failure(IllegalStateException("No Mobile Wallet Adapter wallet installed on this device"))
            is TransactionResult.Failure -> Result.failure(r.e)
        }

    /** Sign and submit one serialized transaction; returns the signature (base58). Used from Phase 4. */
    suspend fun signAndSend(sender: ActivityResultSender, tx: ByteArray): Result<String> {
        val r = adapter.transact(sender) { signAndSendTransactions(arrayOf(tx)) }
        return when (r) {
            is TransactionResult.Success -> {
                val sig = r.payload.signatures.firstOrNull()
                    ?: return Result.failure(IllegalStateException("Wallet returned no signature"))
                Result.success(Base58.encode(sig))
            }
            is TransactionResult.NoWalletFound ->
                Result.failure(IllegalStateException("No Mobile Wallet Adapter wallet installed on this device"))
            is TransactionResult.Failure -> Result.failure(r.e)
        }
    }

    fun disconnect() {
        _address.value = null
    }
}
