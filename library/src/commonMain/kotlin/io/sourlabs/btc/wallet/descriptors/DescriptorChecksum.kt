package io.sourlabs.btc.wallet.descriptors

/**
 * BIP-380 descriptor checksum: an 8-character bech32-style suffix appended to a
 * descriptor body after a `#`. Detects single-character substitutions and short
 * transpositions in the descriptor string.
 *
 * Reference: https://github.com/bitcoin/bips/blob/master/bip-0380.mediawiki
 */
internal object DescriptorChecksum {

    private const val INPUT_CHARSET =
        "0123456789()[],'/*abcdefgh@:\$%{}" +
            "IJKLMNOPQRSTUVWXYZ&+-.;<=>?!^_|~" +
            "ijklmnopqrstuvwxyzABCDEFGH`#\"\\ "

    private const val CHECKSUM_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    private val GENERATOR = longArrayOf(
        0xf5dee51989L,
        0xa9fdca3312L,
        0x1bab10e32dL,
        0x3706b1677aL,
        0x644d626ffdL,
    )

    /**
     * Verify a descriptor of the form `body#checksum`. Returns the body on
     * success. Returns null if the input has no `#`, has a malformed body, or
     * the checksum doesn't match.
     */
    fun verifyAndStrip(input: String): String? {
        val hashIdx = input.lastIndexOf('#')
        if (hashIdx < 0) return null
        val body = input.substring(0, hashIdx)
        val checksum = input.substring(hashIdx + 1)
        if (checksum.length != 8) return null
        if (checksum.any { it !in CHECKSUM_CHARSET }) return null
        val expected = compute(body) ?: return null
        return if (checksum == expected) body else null
    }

    /** Compute the 8-character checksum for a descriptor body. */
    fun compute(body: String): String? {
        val symbols = expand(body) ?: return null
        val padded = symbols + listOf(0, 0, 0, 0, 0, 0, 0, 0)
        val checksum = polymod(padded) xor 1L
        return buildString(8) {
            for (i in 0..7) {
                append(CHECKSUM_CHARSET[((checksum ushr (5 * (7 - i))) and 31L).toInt()])
            }
        }
    }

    private fun polymod(symbols: List<Int>): Long {
        var chk = 1L
        for (value in symbols) {
            val top = chk ushr 35
            chk = ((chk and 0x7ffffffffL) shl 5) xor value.toLong()
            for (i in 0..4) {
                if (((top ushr i) and 1L) == 1L) {
                    chk = chk xor GENERATOR[i]
                }
            }
        }
        return chk
    }

    private fun expand(s: String): List<Int>? {
        val groups = mutableListOf<Int>()
        val symbols = mutableListOf<Int>()
        for (c in s) {
            val v = INPUT_CHARSET.indexOf(c)
            if (v < 0) return null
            symbols.add(v and 31)
            groups.add(v shr 5)
            if (groups.size == 3) {
                symbols.add(groups[0] * 9 + groups[1] * 3 + groups[2])
                groups.clear()
            }
        }
        when (groups.size) {
            1 -> symbols.add(groups[0])
            2 -> symbols.add(groups[0] * 3 + groups[1])
        }
        return symbols
    }
}
