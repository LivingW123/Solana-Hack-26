package app.taptrade.explain

import app.taptrade.data.formatUnits
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The seller reviews a draft (title, price) and then signs a transaction the backend
 * built. Before signing, check that the transaction lists exactly what was reviewed:
 * one TapTrade `create_listing`, signed and paid by the seller, same title, price, mint.
 */
object ListingChecks {
    private val CREATE_LISTING_IX = Anchor.discriminator("global:create_listing")

    fun check(txBytes: ByteArray, programId: String, me: String, mint: String, title: String, price: Long): List<Check> {
        val tx = try {
            TxDecoder.decode(txBytes)
        } catch (e: TxDecodeException) {
            return listOf(Check("Transaction can be read", false, e.message ?: "Malformed"))
        }
        val checks = mutableListOf<Check>()
        checks += Check(
            "Only your wallet signs",
            tx.numRequiredSignatures == 1 && tx.feePayer == me && !tx.usesLookupTables,
            "${tx.numRequiredSignatures} signer(s)",
        )
        val ix = tx.instructions.singleOrNull()
        val isCreate = ix != null && ix.programId == programId && ix.data.size >= 8 &&
            ix.data.copyOf(8).contentEquals(CREATE_LISTING_IX)
        checks += Check(
            "Creates one TapTrade listing",
            isCreate && ix!!.accounts.firstOrNull() == me,
            if (isCreate) "create_listing" else "${tx.instructions.size} instruction(s), not a single create_listing",
        )
        val args = if (isCreate) parseArgs(ix!!.data) else null
        checks += Check(
            "Title and price match what you reviewed",
            args != null && args.title == title && args.price == price && args.mint == mint,
            when {
                args == null -> "Can't read the listing arguments"
                args.title != title -> "Title on-chain would be \"${args.title}\""
                args.price != price -> "Price on-chain would be ${formatUnits(args.price)} tUSDC"
                args.mint != mint -> "Wrong token mint"
                else -> "\"$title\" · ${formatUnits(price)} tUSDC"
            },
        )
        return checks
    }

    private class Args(val price: Long, val mint: String, val title: String)

    /** create_listing(listing_id u64, price u64, mint Pubkey, title String, item_hash [u8; 32]). */
    private fun parseArgs(data: ByteArray): Args? = runCatching {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        b.position(8)
        b.long // listing_id
        val price = b.long
        val mint = ByteArray(32).also { b.get(it) }
        val len = b.int
        require(len in 0..256)
        val title = ByteArray(len).also { b.get(it) }
        Args(price, app.taptrade.wallet.Base58.encode(mint), String(title, Charsets.UTF_8))
    }.getOrNull()
}
