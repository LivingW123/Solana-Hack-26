package app.taptrade.explain

import app.taptrade.wallet.Base58
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Anchor discriminators: first 8 bytes of sha256("account:<Name>") / sha256("global:<ix_name>"). */
object Anchor {
    fun discriminator(preimage: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray()).copyOf(8)

    val ESCROW_ACCOUNT: ByteArray = discriminator("account:Escrow")
    val RELEASE_IX: ByteArray = discriminator("global:release")
}

/** On-chain `Escrow` (anchor/programs/taptrade: buyer, seller, listing, mint, vault, amount, deadline, bump). */
data class EscrowAccount(
    val buyer: String,
    val seller: String,
    val listing: String,
    val mint: String,
    val vault: String,
    /** Base units. */
    val amount: Long,
    val deadline: Long,
) {
    companion object {
        const val SIZE = 8 + 32 * 5 + 8 + 8 + 1

        /** Null unless [data] starts with the Escrow discriminator and is long enough. */
        fun decode(data: ByteArray): EscrowAccount? {
            if (data.size < SIZE || !data.copyOf(8).contentEquals(Anchor.ESCROW_ACCOUNT)) return null
            val key = { i: Int -> Base58.encode(data.copyOfRange(8 + 32 * i, 8 + 32 * (i + 1))) }
            val tail = ByteBuffer.wrap(data, 8 + 32 * 5, 16).order(ByteOrder.LITTLE_ENDIAN)
            return EscrowAccount(key(0), key(1), key(2), key(3), key(4), tail.long, tail.long)
        }
    }
}
