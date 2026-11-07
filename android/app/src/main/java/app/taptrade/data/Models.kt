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
    /** Off-chain metadata from the backend; on-chain only the item hash is stored. */
    val title: String,
    val price: Long,
    val mint: String,
    val status: ListingStatus,
    /** Escrow PDA, present once Funded. */
    val escrow: String? = null,
    val buyer: String? = null,
)

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
)

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

fun shortAddress(a: String): String = if (a.length <= 10) a else "${a.take(4)}…${a.takeLast(4)}"
