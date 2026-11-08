package app.taptrade.tap

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake tap for emulators (no NFC hardware).
 *
 * - Seller: [publish] logs the URL under tag `DebugTap`, where `scripts/tap.sh`
 *   picks it up from the seller emulator's logcat.
 * - Buyer: the URL arrives as a `solana:` deep link (`adb shell am start -d …`,
 *   handled by MainActivity) or from the debug paste field, and goes through [deliver].
 *
 * If no Handoff screen is listening yet, the tap is held in [pending]; the app
 * navigates to the matching listing and the screen picks it up when it calls [listen].
 */
class DeepLinkTapTransport : TapTransport {
    private val main = Handler(Looper.getMainLooper())
    private var listener: ((String) -> Unit)? = null
    private val _pending = MutableStateFlow<String?>(null)

    /** A tap that arrived while no Handoff screen was listening. */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    override fun publish(url: String) {
        Log.i(TAG, "publish: $url")
    }

    override fun listen(onTap: (url: String) -> Unit) {
        listener = onTap
        _pending.value?.let { url ->
            _pending.value = null
            onTap(url)
        }
    }

    override fun stopListening(onTap: (url: String) -> Unit) {
        if (listener === onTap) listener = null
    }

    /** A tap arrived (deep link or paste field). Delivered on the main thread. */
    fun deliver(url: String) {
        Log.i(TAG, "deliver: $url")
        main.post {
            val l = listener
            if (l != null) l(url) else _pending.value = url
        }
    }

    /** Drop a held tap (e.g. it didn't match any of the buyer's escrows). */
    fun clearPending() {
        _pending.value = null
    }

    private companion object {
        const val TAG = "DebugTap"
    }
}
