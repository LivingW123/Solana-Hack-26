package app.taptrade.data

import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Hardcoded backend for the Phase 3 skeleton (`taptrade.fakeBackend=true`).
 * One listing is always "yours" (seller = connected wallet) so both the seller
 * and buyer paths of the UI can be clicked through on each device.
 */
class FakeBackendApi(
    private val baseUrl: String,
    private val tusdcMint: String,
    private val currentWallet: () -> String?,
) : BackendApi {

    private val testSeller = "7vSeVcn4BV59Dy3AUkJuUoaiK4yaCzs6HFyGaKrkNpJS"
    private val testBuyer = "94qGDCxhkRiKorFp4VBoaryXwMPCE4P1RwyVSLtBtPBQ"

    private fun all(): List<Listing> {
        val me = currentWallet()
        val base = listOf(
            Listing("FakeListing1111111111111111111111111111111", testSeller, 1, "Calc textbook, 3rd ed.", 20_000_000, tusdcMint, ListingStatus.Open),
            Listing("FakeListing2222222222222222222222222222222", testSeller, 2, "TI-84 Plus calculator", 35_500_000, tusdcMint, ListingStatus.Open),
            // Funded by the connected wallet → buyer side of the handoff.
            Listing("FakeListing3333333333333333333333333333333", testSeller, 3, "Desk lamp", 8_000_000, tusdcMint, ListingStatus.Funded,
                escrow = "FakeEscrow33333333333333333333333333333333", buyer = me ?: testBuyer),
        )
        // Sold by the connected wallet and already funded → seller side of the handoff.
        val mine = Listing("FakeListing4444444444444444444444444444444", me ?: testSeller, 4, "Mini fridge", 45_000_000, tusdcMint, ListingStatus.Funded,
            escrow = "FakeEscrow44444444444444444444444444444444", buyer = testBuyer)
        return base + mine
    }

    override suspend fun listings(): List<Listing> {
        delay(300)
        return all()
    }

    override suspend fun listing(pubkey: String): Listing {
        delay(150)
        return all().firstOrNull { it.pubkey == pubkey } ?: error("Listing $pubkey not found")
    }

    override suspend fun reputation(wallet: String): Reputation {
        delay(150)
        return if (wallet == testSeller) {
            Reputation(wallet, completed = 12, refunded = 1, disputed = 0, score = 88,
                summary = "12 completed trades, 1 refund since September.")
        } else {
            Reputation(wallet, completed = 0, refunded = 0, disputed = 0, score = 50,
                summary = "New wallet — no on-chain trades yet.")
        }
    }

    override suspend fun createListingTx(seller: String, title: String, price: Long): TxResponse =
        throw UnsupportedOperationException("Listing needs the real backend (taptrade.fakeBackend=false)")

    override suspend fun fundTx(listing: String, buyer: String): TxResponse =
        throw UnsupportedOperationException("Funding needs the real backend (taptrade.fakeBackend=false)")

    override suspend fun transactionRequest(link: String, account: String): TxResponse =
        throw UnsupportedOperationException("Release needs the real backend (taptrade.fakeBackend=false)")

    override suspend fun handoff(escrow: String, seller: String): HandoffTicket {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        return HandoffTicket(
            url = "solana:${baseUrl.trimEnd('/')}/tx/release?escrow=$escrow&nonce=$nonce",
            expiresAt = System.currentTimeMillis() + 120_000,
        )
    }
}
