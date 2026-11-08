package app.taptrade.explain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Rule checks against real devnet data: `release-fixture.json` is written by
 * `WRITE_FIXTURE=1 npm run e2e` in backend/ (with DEMO_TAMPER=1 for the tampered tx).
 */
class ReleaseChecksTest {
    private val f = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("release-fixture.json")!!.readText()
    ).jsonObject

    private fun s(k: String) = f[k]!!.jsonPrimitive.content
    private fun b64(k: String) = Base64.getDecoder().decode(s(k))

    private val backend = s("backendUrl")
    private val escrow = s("escrow")

    private fun ctx(
        me: String = s("me"),
        price: Long = f["price"]!!.jsonPrimitive.long,
        link: String? = "$backend/tx/release?escrow=$escrow&nonce=abc",
        urlEscrow: String? = escrow,
    ) = ReleaseContext(
        programId = s("programId"),
        trustedBackendUrl = backend,
        me = me,
        listing = s("listing"),
        listingTitle = "E2E test item",
        listingPrice = price,
        listingEscrow = escrow,
        link = link,
        urlEscrow = urlEscrow,
    )

    private val chain = ChainState(s("escrowOwner"), b64("escrowData"), f["vaultBalance"]!!.jsonPrimitive.long)

    private fun failed(checks: List<Check>) = checks.filter { !it.ok }.map { it.rule }

    @Test
    fun escrowAccountDecodes() {
        val e = EscrowAccount.decode(b64("escrowData"))
        assertNotNull(e)
        assertEquals(s("me"), e!!.buyer)
        assertEquals(s("listing"), e.listing)
        assertEquals(f["price"]!!.jsonPrimitive.long, e.amount)
    }

    @Test
    fun genuineReleasePassesEveryRule() {
        val checks = ReleaseChecks.checkLink(ctx()) + ReleaseChecks.checkTx(ctx(), b64("releaseTx"), chain)
        assertEquals(emptyList<String>(), failed(checks))
        assertTrue(ReleaseChecks.fallbackSummary(ctx(), chain).startsWith("Releases 1 tUSDC to "))
    }

    @Test
    fun hiddenTokenApprovalIsBlocked() {
        if (f["tamperedTx"] is JsonNull) return // fixture written without DEMO_TAMPER
        val checks = ReleaseChecks.checkTx(ctx(), b64("tamperedTx"), chain)
        val programs = checks.single { it.rule == "Calls only the TapTrade program" }
        assertFalse(programs.ok)
        assertTrue(programs.detail, programs.detail.contains("Approve"))
    }

    @Test
    fun foreignHostIsBlockedBeforeContactingIt() {
        val checks = ReleaseChecks.checkLink(ctx(link = "http://10.0.2.2:3001/tx/release?escrow=$escrow&nonce=x"))
        assertEquals(listOf("Link comes from TapTrade"), failed(checks))
    }

    @Test
    fun otherEscrowInUrlIsBlocked() {
        val checks = ReleaseChecks.checkLink(ctx(urlEscrow = "11111111111111111111111111111111"))
        assertEquals(listOf("Tap is for this listing's escrow"), failed(checks))
    }

    @Test
    fun wrongWalletIsBlocked() {
        val other = "7vSeVcn4BV59Dy3AUkJuUoaiK4yaCzs6HFyGaKrkNpJS"
        val bad = failed(ReleaseChecks.checkTx(ctx(me = other), b64("releaseTx"), chain))
        assertTrue(bad.toString(), "You funded this escrow" in bad && "Only your wallet signs" in bad)
    }

    @Test
    fun priceMismatchIsBlocked() {
        val bad = failed(ReleaseChecks.checkTx(ctx(price = 2_000_000), b64("releaseTx"), chain))
        assertEquals(listOf("Amount is 2 tUSDC"), bad)
    }

    @Test
    fun missingOrForeignEscrowIsBlocked() {
        val gone = failed(ReleaseChecks.checkTx(ctx(), b64("releaseTx"), ChainState(null, null, null)))
        assertTrue(gone.toString(), "Escrow is live on Solana" in gone)
        val foreign = failed(ReleaseChecks.checkTx(ctx(), b64("releaseTx"), chain.copy(escrowOwner = s("me"))))
        assertTrue(foreign.toString(), "Escrow is live on Solana" in foreign)
    }

    @Test
    fun garbageIsBlocked() {
        val checks = ReleaseChecks.checkTx(ctx(), byteArrayOf(1, 2, 3), chain)
        assertEquals(listOf("Transaction can be read"), failed(checks))
    }
}
