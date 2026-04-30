package io.sourlabs.btc.wallet.descriptors

import io.sourlabs.btc.wallet.api.DescriptorException
import io.sourlabs.btc.wallet.models.Network

/**
 * BIP-380 descriptor parser, restricted to the single-key subset required for
 * watch-only wallet import. See [Descriptor] for the supported wrappers.
 *
 * Implementation note: this is a single-pass recursive descent over a stripped
 * descriptor body (checksum verified up front by [DescriptorChecksum]).
 */
internal object DescriptorParser {

    private val MAINNET_PREFIXES = setOf("xpub", "ypub", "zpub")
    private val TESTNET_PREFIXES = setOf("tpub", "upub", "vpub")
    private val SUPPORTED_XPUB_PREFIXES = MAINNET_PREFIXES + TESTNET_PREFIXES

    private val ACCEPTED_DERIVATION_SUFFIXES = setOf(
        "",
        "/0/*",
        "/<0;1>/*",
        "/{0,1}/*",
    )

    fun parse(input: String): Descriptor {
        val body = DescriptorChecksum.verifyAndStrip(input)
            ?: throw DescriptorException.InvalidChecksum()
        return parseBody(body)
    }

    private fun parseBody(body: String): Descriptor {
        val openIdx = body.indexOf('(')
        if (openIdx <= 0 || !body.endsWith(')')) {
            throw DescriptorException.Malformed("expected wrapper(...) form, got '$body'")
        }
        val name = body.substring(0, openIdx)
        val inner = body.substring(openIdx + 1, body.length - 1)
        return when (name) {
            "pkh" -> {
                val key = parseKeyExpression(inner)
                Descriptor.Pkh(key.origin, key.xpub, key.network)
            }
            "wpkh" -> {
                val key = parseKeyExpression(inner)
                Descriptor.Wpkh(key.origin, key.xpub, key.network)
            }
            "tr" -> {
                if (containsTopLevelComma(inner)) {
                    throw DescriptorException.Unsupported(
                        "tr() with a script tree is not supported (key-path only)"
                    )
                }
                val key = parseKeyExpression(inner)
                Descriptor.Tr(key.origin, key.xpub, key.network)
            }
            "sh" -> parseShWrapped(inner)
            "wsh" -> throw DescriptorException.Unsupported("wsh() is not supported")
            "multi", "sortedmulti", "multi_a", "sortedmulti_a", "musig" ->
                throw DescriptorException.Unsupported(
                    "$name() is not supported (multi-signature/MuSig2)"
                )
            "pk" -> throw DescriptorException.Unsupported(
                "pk() is not supported (use pkh/wpkh/tr for watch-only import)"
            )
            "addr" -> throw DescriptorException.Unsupported("addr() is not supported (raw address)")
            "raw" -> throw DescriptorException.Unsupported("raw() is not supported (raw script)")
            "combo" -> throw DescriptorException.Unsupported("combo() is not supported")
            else -> throw DescriptorException.Unsupported("unknown descriptor function: $name()")
        }
    }

    private fun parseShWrapped(inner: String): Descriptor.ShWpkh {
        val openIdx = inner.indexOf('(')
        if (openIdx <= 0 || !inner.endsWith(')')) {
            throw DescriptorException.Malformed("expected sh(WRAPPER(...))")
        }
        val innerName = inner.substring(0, openIdx)
        if (innerName != "wpkh") {
            throw DescriptorException.Unsupported(
                "only sh(wpkh(...)) is supported within sh(); got sh($innerName(...))"
            )
        }
        val keyText = inner.substring(openIdx + 1, inner.length - 1)
        val key = parseKeyExpression(keyText)
        return Descriptor.ShWpkh(key.origin, key.xpub, key.network)
    }

    private data class ParsedKey(
        val origin: Descriptor.KeyOrigin?,
        val xpub: String,
        val network: Network,
    )

    private fun parseKeyExpression(text: String): ParsedKey {
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
        return ParsedKey(origin, xpub, network)
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

    /**
     * Detect a comma at the outermost nesting level of a wrapper's argument
     * list. Used to flag a `tr(KEY, TREE)` script-tree form as unsupported.
     */
    private fun containsTopLevelComma(text: String): Boolean {
        var depth = 0
        for (c in text) {
            when (c) {
                '(', '{', '<' -> depth++
                ')', '}', '>' -> depth--
                ',' -> if (depth == 0) return true
            }
        }
        return false
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
