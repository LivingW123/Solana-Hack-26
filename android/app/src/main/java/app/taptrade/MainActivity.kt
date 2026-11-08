package app.taptrade

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import app.taptrade.tap.DeepLinkTapTransport
import app.taptrade.ui.LocalActivityResultSender
import app.taptrade.ui.LocalAppContainer
import app.taptrade.ui.TapTradeNav
import app.taptrade.ui.theme.TapTradeTheme
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

class MainActivity : ComponentActivity() {
    // Must be created before the activity is STARTED (it registers an activity-result launcher).
    private lateinit var sender: ActivityResultSender

    private val container get() = (application as TapTradeApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sender = ActivityResultSender(this)

        setContent {
            TapTradeTheme {
                CompositionLocalProvider(
                    LocalAppContainer provides container,
                    LocalActivityResultSender provides sender,
                ) {
                    TapTradeNav()
                }
            }
        }
        // Cold start from a tap; skip on recreation (rotation) so it isn't replayed.
        if (savedInstanceState == null) handleTap(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTap(intent)
    }

    /** A `solana:` VIEW intent is the fake tap (scripts/tap.sh); hand it to the tap transport. */
    private fun handleTap(intent: Intent?) {
        val url = intent?.dataString ?: return
        if (intent.action != Intent.ACTION_VIEW || !url.startsWith("solana:")) return
        (container.tapTransport as? DeepLinkTapTransport)?.deliver(url)
    }
}
