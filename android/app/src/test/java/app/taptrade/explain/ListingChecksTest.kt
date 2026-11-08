package app.taptrade.explain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Draft → create_listing tx check, against a real devnet draft (`listing-fixture.json`, written by e2e). */
class ListingChecksTest {
    private val f = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("listing-fixture.json")!!.readText()
    ).jsonObject

    private fun s(k: String) = f[k]!!.jsonPrimitive.content
    private val tx = Base64.getDecoder().decode(s("tx"))
    private val price = f["price"]!!.jsonPrimitive.long

    private fun failed(title: String = s("title"), p: Long = price, me: String = s("seller"), mint: String = s("mint")) =
        ListingChecks.check(tx, s("programId"), me, mint, title, p).filter { !it.ok }.map { it.rule }

    @Test
    fun draftTxMatchesReviewedDraft() = assertEquals(emptyList<String>(), failed())

    @Test
    fun differentTitleOrPriceIsCaught() {
        assertEquals(listOf("Title and price match what you reviewed"), failed(title = "Something else"))
        assertEquals(listOf("Title and price match what you reviewed"), failed(p = price + 1))
    }

    @Test
    fun wrongSignerOrMintIsCaught() {
        val other = "94qGDCxhkRiKorFp4VBoaryXwMPCE4P1RwyVSLtBtPBQ"
        assertTrue("Only your wallet signs" in failed(me = other))
        assertEquals(listOf("Title and price match what you reviewed"), failed(mint = other))
    }
}
