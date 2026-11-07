package app.taptrade.wallet

/** Minimal Base58 (Bitcoin alphabet) for Solana addresses and signatures. */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        val zeros = input.takeWhile { it.toInt() == 0 }.count()
        val digits = input.copyOf()
        val out = StringBuilder()
        var start = zeros
        while (start < digits.size) {
            var rem = 0
            for (i in start until digits.size) {
                val acc = rem * 256 + (digits[i].toInt() and 0xff)
                digits[i] = (acc / 58).toByte()
                rem = acc % 58
            }
            out.append(ALPHABET[rem])
            while (start < digits.size && digits[start].toInt() == 0) start++
        }
        repeat(zeros) { out.append('1') }
        return out.reverse().toString()
    }
}
