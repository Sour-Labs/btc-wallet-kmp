package io.sourlabs.btc.wallet.descriptors

import io.sourlabs.btc.wallet.api.DescriptorException

/**
 * BIP-380 descriptor parser, restricted to the single-key subset required for
 * watch-only wallet import. See [Descriptor] for the supported wrappers.
 *
 * Implementation note: this is a single-pass recursive descent over a stripped
 * descriptor body (checksum verified up front by [DescriptorChecksum]).
 */
internal object DescriptorParser {

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
                val key = KeyExpressionParser.parse(inner)
                Descriptor.Pkh(key.origin, key.xpub, key.network)
            }
            "wpkh" -> {
                val key = KeyExpressionParser.parse(inner)
                Descriptor.Wpkh(key.origin, key.xpub, key.network)
            }
            "tr" -> {
                if (containsTopLevelComma(inner)) {
                    throw DescriptorException.Unsupported(
                        "tr() with a script tree is not supported (key-path only)"
                    )
                }
                val key = KeyExpressionParser.parse(inner)
                Descriptor.Tr(key.origin, key.xpub, key.network)
            }
            "sh" -> parseShWrapped(inner)
            "wsh" -> throw DescriptorException.Unsupported(
                "wsh() is not supported by Descriptor.parse — use MultisigDescriptor.parse or " +
                    "OutputDescriptor.parse for wsh(sortedmulti(...)) descriptors"
            )
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
        val key = KeyExpressionParser.parse(keyText)
        return Descriptor.ShWpkh(key.origin, key.xpub, key.network)
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
}
