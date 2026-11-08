package app.taptrade.explain

import app.taptrade.wallet.Base58

/** One compiled instruction, with its program and accounts resolved to base58 addresses. */
class DecodedIx(val programId: String, val accounts: List<String>, val data: ByteArray)

/**
 * A Solana wire-format transaction, decoded without trusting whoever built it.
 * [usesLookupTables] = v0 message with address lookup tables; those accounts can't be
 * verified from the bytes alone, so the rule checks reject such transactions.
 */
class DecodedTx(
    val version: Int?,
    val numRequiredSignatures: Int,
    val accountKeys: List<String>,
    val instructions: List<DecodedIx>,
    val usesLookupTables: Boolean,
) {
    val feePayer: String? get() = accountKeys.firstOrNull()
}

class TxDecodeException(message: String) : Exception(message)

/** Decodes legacy and v0 transactions (signatures + message). */
object TxDecoder {
    fun decode(bytes: ByteArray): DecodedTx {
        val r = Reader(bytes)
        val sigCount = r.shortVec()
        r.skip(64 * sigCount)

        val first = r.u8()
        val version: Int?
        val numRequired: Int
        if (first and 0x80 != 0) {
            version = first and 0x7f
            if (version != 0) throw TxDecodeException("Unsupported transaction version $version")
            numRequired = r.u8()
        } else {
            version = null
            numRequired = first
        }
        r.u8() // readonly signed
        r.u8() // readonly unsigned

        val keys = List(r.shortVec()) { Base58.encode(r.bytes(32)) }
        r.skip(32) // recent blockhash

        val raw = List(r.shortVec()) {
            val programIndex = r.u8()
            val accountIdx = List(r.shortVec()) { r.u8() }
            val data = r.bytes(r.shortVec())
            Triple(programIndex, accountIdx, data)
        }

        var lookups = false
        if (version == 0) {
            val tables = r.shortVec()
            repeat(tables) {
                r.skip(32)
                r.skip(r.shortVec())
                r.skip(r.shortVec())
            }
            lookups = tables > 0
        }

        val key = { i: Int -> keys.getOrNull(i) ?: if (lookups) "lookup#$i" else throw TxDecodeException("Account index $i out of range") }
        val ixs = raw.map { (p, a, d) -> DecodedIx(key(p), a.map(key), d) }
        return DecodedTx(version, numRequired, keys, ixs, lookups)
    }

    private class Reader(private val b: ByteArray) {
        private var pos = 0

        fun u8(): Int {
            if (pos >= b.size) throw TxDecodeException("Truncated transaction")
            return b[pos++].toInt() and 0xff
        }

        fun bytes(n: Int): ByteArray {
            if (n < 0 || pos + n > b.size) throw TxDecodeException("Truncated transaction")
            return b.copyOfRange(pos, pos + n).also { pos += n }
        }

        fun skip(n: Int) {
            bytes(n)
        }

        /** Solana "compact-u16": 7 bits per byte, high bit = continue. */
        fun shortVec(): Int {
            var value = 0
            var shift = 0
            while (true) {
                val byte = u8()
                value = value or ((byte and 0x7f) shl shift)
                if (byte and 0x80 == 0) return value
                shift += 7
                if (shift > 14) throw TxDecodeException("Bad length prefix")
            }
        }
    }
}
