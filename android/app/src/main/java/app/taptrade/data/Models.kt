package app.taptrade.data

import kotlinx.serialization.Serializable

/** Amounts are integer token base units everywhere; format only in UI. */
const val TUSDC_DECIMALS = 6

@Serializable
enum class ListingStatus { Open, Funded, Closed, Cancelled }

@Serializable
data class Listing(
    /** Listing PDA. */
    val pubkey: String,
    val seller: String,
    val listingId: Long,
    /** On-chain (≤ 64 bytes). */
    val title: String,
    val price: Long,
    val mint: String,
    val status: ListingStatus,
    /** Escrow PDA, present once Funded. */
    val escrow: String? = null,
    val buyer: String? = null,
    /** Unix seconds; after this anyone can refund the buyer. */
    val deadline: Long? = null,
)

/** Max on-chain title length in UTF-8 bytes (program's MAX_TITLE_LEN). */
const val MAX_TITLE_BYTES = 64

@Serializable
data class Reputation(
    val wallet: String,
    val completed: Int,
    val refunded: Int,
    val disputed: Int,
    /** 0..100, computed by the backend from on-chain counters. */
    val score: Int,
    val summary: String,
)

/** Unsigned transaction from the backend, base64-encoded. */
@Serializable
data class TxResponse(
    val transaction: String,
    val message: String? = null,
    /** create-listing: the new Listing PDA. */
    val listing: String? = null,
    /** fund: the Escrow PDA. */
    val escrow: String? = null,
)

/** `/ai/explain` result. `source` = "llm" or "fallback". Display only; never gates signing. */
@Serializable
data class Explanation(val summary: String, val source: String = "fallback")

/** Seller's handoff ticket: Solana Pay URL with a one-time nonce. */
@Serializable
data class HandoffTicket(
    val url: String,
    val expiresAt: Long,
)

fun formatUnits(amount: Long, decimals: Int = TUSDC_DECIMALS): String {
    val s = amount.toString().padStart(decimals + 1, '0')
    val whole = s.dropLast(decimals)
    val frac = s.takeLast(decimals).trimEnd('0')
    return if (frac.isEmpty()) whole else "$whole.$frac"
}

/** "20" / "8.5" tUSDC → base units; null if not a positive amount with ≤ [decimals] places. */
fun parseUnits(text: String, decimals: Int = TUSDC_DECIMALS): Long? = runCatching {
    java.math.BigDecimal(text.trim()).movePointRight(decimals).longValueExact().takeIf { it > 0 }
}.getOrNull()

fun shortAddress(a: String): String = if (a.length <= 10) a else "${a.take(4)}…${a.takeLast(4)}"
