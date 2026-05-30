package io.sourlabs.btc.wallet.descriptors

import io.sourlabs.btc.wallet.api.DescriptorException
import io.sourlabs.btc.wallet.models.Network

/**
 * Shared BIP-380 key-expression parser used by both the single-key
 * [DescriptorParser] and the multisig [MultisigDescriptorParser].
 *
 * A key expression is `[fingerprint/path]?xpub[/derivation-suffix]?` where:
 *  - the bracketed `[fingerprint/path]` key origin is optional;
 *  - the extended public key must use a recognised SLIP-132 prefix; and
 *  - the derivation suffix is restricted to the receive-only, multipath, and
 *    BIP-389 brace-list forms (see [ACCEPTED_DERIVATION_SUFFIXES]), or absent.
 *
 * Extracted from the legacy private parser so the multisig path can reuse the
 * exact same grammar — keeping a single source of truth for "what counts as a
 * valid descriptor key" across single-key and multisig descriptors.
 */
internal object KeyExpressionParser {

    private val MAINNET_PREFIXES = setOf("xpub", "ypub", "zpub")
    private val TESTNET_PREFIXES = setOf("tpub", "upub", "vpub")
    private val SUPPORTED_XPUB_PREFIXES = MAINNET_PREFIXES + TESTNET_PREFIXES

    val ACCEPTED_DERIVATION_SUFFIXES = setOf(
        "",
        "/0/*",
        "/<0;1>/*",
        "/{0,1}/*",
    )

    data class ParsedKey(
        val origin: Descriptor.KeyOrigin?,
        val xpub: String,
        val network: Network,
        /**
         * The derivation suffix exactly as it appeared after the xpub
         * (e.g. the receive-only suffix or the empty string). Multisig descriptors enforce that all keys
         * share the same suffix; single-key callers ignore this.
         */
        val suffix: String,
    )

    fun parse(text: String): ParsedKey {
        var pos = 0
        val origin = if (text.startsWith('[')) {
            val close = text.indexOf(']')
            if (close < 0) throw DescriptorException.Malformed("unterminated key origin")
            val parsed = parseKeyOrigin(text.substring(1, close))
            pos = close + 1
            parsed
        } else null

        val rest = text.substring(pos)
        if (rest.length < 4) {
            throw DescriptorException.Malformed("missing extended public key")
        }
        val prefix = rest.substring(0, 4)
        if (prefix !in SUPPORTED_XPUB_PREFIXES) {
            throw DescriptorException.Unsupported(
                "expected an extended public key (xpub/ypub/zpub/tpub/upub/vpub); got prefix '$prefix'"
            )
        }

        val derivationStart = rest.indexOf('/', startIndex = 4)
        val xpub: String
        val suffix: String
        if (derivationStart < 0) {
            xpub = rest
            suffix = ""
        } else {
            xpub = rest.substring(0, derivationStart)
            suffix = rest.substring(derivationStart)
        }

        if (suffix !in ACCEPTED_DERIVATION_SUFFIXES) {
            throw DescriptorException.Unsupported(
                "unsupported derivation suffix '$suffix'; only /0/*, /<0;1>/*, and /{0,1}/* are accepted"
            )
        }

        val network = if (prefix in MAINNET_PREFIXES) Network.MAINNET else Network.TESTNET
        return ParsedKey(origin, xpub, network, suffix)
    }

    private fun parseKeyOrigin(text: String): Descriptor.KeyOrigin {
        val firstSlash = text.indexOf('/')
        val fpHex = if (firstSlash < 0) text else text.substring(0, firstSlash)
        if (fpHex.length != 8 || !fpHex.all { it.isHexDigit() }) {
            throw DescriptorException.Malformed(
                "key origin fingerprint must be 8 hex characters; got '$fpHex'"
            )
        }
        val fingerprint = ByteArray(4) { i ->
            fpHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val path = if (firstSlash < 0 || firstSlash == text.lastIndex) emptyList()
        else parsePath(text.substring(firstSlash + 1))
        return Descriptor.KeyOrigin(fingerprint, path)
    }

    private fun parsePath(text: String): List<Descriptor.PathStep> =
        text.split('/').map { parsePathStep(it) }

    private fun parsePathStep(step: String): Descriptor.PathStep {
        if (step.isEmpty()) throw DescriptorException.Malformed("empty path step in key origin")
        val last = step.last()
        val (numberPart, hardened) = when (last) {
            '\'', 'h' -> step.dropLast(1) to true
            else -> step to false
        }
        val index = numberPart.toLongOrNull()
            ?: throw DescriptorException.Malformed("invalid path step '$step'")
        if (index < 0 || index > 0x7fffffffL) {
            throw DescriptorException.Malformed("path step out of range: '$step'")
        }
        return Descriptor.PathStep(index, hardened)
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
