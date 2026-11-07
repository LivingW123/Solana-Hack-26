package app.taptrade

import android.app.Application
import app.taptrade.data.BackendApi
import app.taptrade.data.FakeBackendApi
import app.taptrade.data.HttpBackendApi
import app.taptrade.tap.DebugTapTransport
import app.taptrade.tap.NfcTapTransport
import app.taptrade.tap.TapTransport
import app.taptrade.wallet.WalletManager

/** Manual DI: one container per process. Swap implementations here. */
class AppContainer {
    val wallet = WalletManager()

    val backend: BackendApi =
        if (BuildConfig.FAKE_BACKEND) {
            FakeBackendApi(BuildConfig.BACKEND_URL, BuildConfig.TUSDC_MINT) { wallet.address.value }
        } else {
            HttpBackendApi(BuildConfig.BACKEND_URL)
        }

    /** Debug builds use the fake tap; release would use NFC (out of scope). */
    val tapTransport: TapTransport =
        if (BuildConfig.DEBUG) DebugTapTransport() else NfcTapTransport()
}

class TapTradeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer()
    }
}
