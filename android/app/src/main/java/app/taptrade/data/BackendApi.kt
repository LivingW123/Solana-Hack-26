package app.taptrade.data

/**
 * Backend calls (see CLAUDE.md "Backend API"). The backend builds unsigned
 * transactions and reads chain state; it never holds user keys.
 */
interface BackendApi {
    suspend fun listings(): List<Listing>

    suspend fun listing(pubkey: String): Listing

    suspend fun reputation(wallet: String): Reputation

    /** Unsigned `fund_escrow` tx for [buyer]. */
    suspend fun fundTx(listing: String, buyer: String): TxResponse

    /** Seller: get a Solana Pay `release` URL with a fresh one-time nonce. */
    suspend fun handoff(escrow: String, seller: String): HandoffTicket
}
