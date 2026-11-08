package app.taptrade.data

/**
 * Backend calls (see CLAUDE.md "Backend API"). The backend builds unsigned
 * transactions and indexes chain state; it never holds user keys, and the app
 * confirms anything money-related straight from RPC (`SolanaRpc`).
 */
interface BackendApi {
    suspend fun listings(): List<Listing>

    suspend fun listing(pubkey: String): Listing

    suspend fun reputation(wallet: String): Reputation

    /** Unsigned `create_listing` tx; [price] in base units. */
    suspend fun createListingTx(seller: String, title: String, price: Long): TxResponse

    /** Unsigned `fund_escrow` tx for [buyer] (Solana Pay / Actions POST). */
    suspend fun fundTx(listing: String, buyer: String): TxResponse

    /** Seller: get a Solana Pay `release` URL with a fresh one-time nonce. */
    suspend fun handoff(escrow: String, seller: String): HandoffTicket

    /** Buyer: Solana Pay transaction request — POST [account] to the tapped [link] (https URL, no `solana:`). */
    suspend fun transactionRequest(link: String, account: String): TxResponse
}
