package app.taptrade

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import app.taptrade.ui.LocalActivityResultSender
import app.taptrade.ui.LocalAppContainer
import app.taptrade.ui.TapTradeNav
import app.taptrade.ui.theme.TapTradeTheme
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

class MainActivity : ComponentActivity() {
    // Must be created before the activity is STARTED (it registers an activity-result launcher).
    private lateinit var sender: ActivityResultSender

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sender = ActivityResultSender(this)
        val container = (application as TapTradeApp).container

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
    }
}
