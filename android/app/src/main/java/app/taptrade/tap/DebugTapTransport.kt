package app.taptrade.tap

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Fake tap for emulators. `publish` only records/logs the URL; the URL reaches
 * the buyer through [deliver], which is called by:
 *  - the in-app "simulate tap" field on the Handoff screen (Phase 3), and
 *  - the `solana:` deep link from `scripts/tap.sh` via adb (Phase 5).
 */
class DebugTapTransport : TapTransport {
    private val main = Handler(Looper.getMainLooper())
    private var listener: ((String) -> Unit)? = null

    @Volatile
    var lastPublished: String? = null
        private set

    override fun publish(url: String) {
        lastPublished = url
        Log.i(TAG, "publish: $url")
    }

    override fun listen(onTap: (url: String) -> Unit) {
        listener = onTap
    }

    /** Deliver a tapped URL to the current listener (on the main thread). */
    fun deliver(url: String) {
        Log.i(TAG, "deliver: $url")
        main.post { listener?.invoke(url) }
    }

    private companion object {
        const val TAG = "DebugTap"
    }
}
