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
    /** Distinct wallets this one completed trades with (from its on-chain history). */
    val distinctCounterparties: Int = 0,
    /** Completed trades that count: at most 3 per counterparty. */
    val effectiveCompleted: Int = 0,
    /** "New" | "Building" | "Trusted" | "Caution". */
    val level: String = "",
    /** The score formula, shown so the number is explainable. */
    val formula: String = "",
    val summarySource: String = "fallback",
    /** The wallet's `Reputation` PDA (where the counters live); empty if the backend didn't send it. */
    val account: String = "",
)

/** `/ai/draft-listing` draft: what the seller reviews before signing. */
@Serializable
data class Draft(
    val title: String,
    /** Base units. */
    val price: Long,
    val priceUsdc: Double,
    val deadlineIso: String,
    val category: String,
    /** "llm" or "rules". */
    val source: String,
)

@Serializable
data class DraftResponse(
    val draft: Draft,
    val transaction: String? = null,
    val message: String? = null,
    val listing: String? = null,
)

/** Solana Explorer links (devnet), so anyone can verify what the app shows. */
object Explorer {
    fun tx(signature: String) = "https://explorer.solana.com/tx/$signature?cluster=devnet"
    fun address(pubkey: String) = "https://explorer.solana.com/address/$pubkey?cluster=devnet"
}

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
