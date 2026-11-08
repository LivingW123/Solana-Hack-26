package app.taptrade

import android.app.Application
import app.taptrade.data.BackendApi
import app.taptrade.data.FakeBackendApi
import app.taptrade.data.HttpBackendApi
import app.taptrade.data.SolanaRpc
import app.taptrade.tap.DeepLinkTapTransport
import app.taptrade.tap.NfcTapTransport
import app.taptrade.tap.TapTransport
import app.taptrade.wallet.WalletManager

/** Manual DI: one container per process. Swap implementations here. */
class AppContainer {
    val wallet = WalletManager()

    /** Direct chain reads: confirmations and the seller's escrow websocket. */
    val rpc = SolanaRpc(BuildConfig.RPC_URL)

    val backend: BackendApi =
        if (BuildConfig.FAKE_BACKEND) {
            FakeBackendApi(BuildConfig.BACKEND_URL, BuildConfig.TUSDC_MINT) { wallet.address.value }
        } else {
            HttpBackendApi(BuildConfig.BACKEND_URL)
        }

    /** Debug builds use the fake tap; release would use NFC (out of scope). */
    val tapTransport: TapTransport =
        if (BuildConfig.DEBUG) DeepLinkTapTransport() else NfcTapTransport()
}

class TapTradeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer()
    }
}
