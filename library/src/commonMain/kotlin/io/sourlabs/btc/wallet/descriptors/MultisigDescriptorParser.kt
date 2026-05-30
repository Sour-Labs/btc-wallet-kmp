package io.sourlabs.btc.wallet.descriptors

import io.sourlabs.btc.wallet.api.DescriptorException
import io.sourlabs.btc.wallet.models.Network

/**
 * Parser for the multisig subset of BIP-380 output descriptors. Mirrors
 * [DescriptorParser] in shape (single-pass recursive descent over a stripped
 * descriptor body) but produces [MultisigDescriptor] variants instead.
 *
 * Recognised forms:
 *  - `wsh(sortedmulti(M, KEY1, ..., KEYN))` — the only one supported today;
 *    every modern hardware-multisig export (Bitkey, Sparrow, BlueWallet, …)
 *    uses this.
 *
 * Explicitly rejected here so callers get a typed reason rather than the
 * generic "not a single-key descriptor":
 *  - bare `multi(...)` / `sortedmulti(...)` (P2MS, non-standard)
 *  - `wsh(multi(...))` (unsorted multisig; no production wallet emits this)
 *  - `wsh(<anything else>)` (miniscript)
 */
internal object MultisigDescriptorParser {

    fun parse(input: String): MultisigDescriptor {
        val body = DescriptorChecksum.verifyAndStrip(input)
            ?: throw DescriptorException.InvalidChecksum()
        return parseBody(body)
    }

    private fun parseBody(body: String): MultisigDescriptor {
        val openIdx = body.indexOf('(')
        if (openIdx <= 0 || !body.endsWith(')')) {
            throw DescriptorException.Malformed("expected wsh(sortedmulti(...)) form, got '$body'")
        }
        val name = body.substring(0, openIdx)
        val inner = body.substring(openIdx + 1, body.length - 1)
        return when (name) {
            "wsh" -> parseWshWrapped(inner)
            "multi", "sortedmulti" -> throw DescriptorException.Unsupported(
                "$name() at the top level is bare P2MS and not standard; " +
                    "use wsh(sortedmulti(...)) for native-segwit multisig"
            )
            else -> throw DescriptorException.Unsupported(
                "expected wsh(...) at the top level for a multisig descriptor; got $name()"
            )
        }
    }

    private fun parseWshWrapped(inner: String): MultisigDescriptor {
        val openIdx = inner.indexOf('(')
        if (openIdx <= 0 || !inner.endsWith(')')) {
            throw DescriptorException.Malformed("expected wsh(FUNC(...)) form")
        }
        val innerName = inner.substring(0, openIdx)
        val args = inner.substring(openIdx + 1, inner.length - 1)
        return when (innerName) {
            "sortedmulti" -> parseSortedMulti(args)
            "multi" -> throw DescriptorException.Unsupported(
                "wsh(multi(...)) (unsorted multisig) is not supported yet; " +
                    "use wsh(sortedmulti(...)) for canonical BIP-67 key ordering"
            )
            else -> throw DescriptorException.Unsupported(
                "wsh($innerName(...)) is not supported (only wsh(sortedmulti(...)) for now)"
            )
        }
    }

    private fun parseSortedMulti(args: String): MultisigDescriptor.WshSortedMulti {
        val parts = splitTopLevel(args)
        if (parts.size < 2) {
            throw DescriptorException.Malformed(
                "sortedmulti requires a threshold and at least one key; got '$args'"
            )
        }
        val threshold = parts.first().trim().toIntOrNull()
            ?: throw DescriptorException.Malformed(
                "sortedmulti threshold must be a positive integer; got '${parts.first().trim()}'"
            )
        if (threshold < 1) {
            throw DescriptorException.Malformed("sortedmulti threshold must be ≥ 1; got $threshold")
        }
        val keyExprs = parts.drop(1).map { it.trim() }
        if (threshold > keyExprs.size) {
            throw DescriptorException.Malformed(
                "sortedmulti threshold $threshold exceeds key count ${keyExprs.size}"
            )
        }
        // 16 is the OP_CHECKMULTISIG signature/pubkey ceiling enforced by the
        // script interpreter (OP_M / OP_N use the OP_1..OP_16 opcodes).
        if (keyExprs.size > 16) {
            throw DescriptorException.Unsupported(
                "sortedmulti supports at most 16 keys; got ${keyExprs.size}"
            )
        }

        val parsedKeys: List<KeyExpressionParser.ParsedKey> =
            keyExprs.map { KeyExpressionParser.parse(it) }

        val networks: Set<Network> = parsedKeys.map { it.network }.toSet()
        if (networks.size != 1) {
            throw DescriptorException.Malformed(
                "all cosigner keys must target the same network; got $networks"
            )
        }

        val suffixes: Set<String> = parsedKeys.map { it.suffix }.toSet()
        if (suffixes.size != 1) {
            throw DescriptorException.Malformed(
                "all cosigner keys must share the same derivation suffix; got $suffixes"
            )
        }

        return MultisigDescriptor.WshSortedMulti(
            threshold = threshold,
            keys = parsedKeys.map { MultisigDescriptor.Key(it.origin, it.xpub) },
            network = networks.single(),
        )
    }

    /**
     * Split a descriptor argument list on top-level commas. Nested `()`, `{}`,
     * and `<>` groups (e.g. the `<0;1>` in a multipath suffix) are skipped so
     * we don't split mid-expression.
     */
    private fun splitTopLevel(args: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in args.indices) {
            when (args[i]) {
                '(', '{', '<' -> depth++
                ')', '}', '>' -> depth--
                ',' -> if (depth == 0) {
                    parts.add(args.substring(start, i))
                    start = i + 1
                }
            }
        }
        parts.add(args.substring(start))
        return parts
    }
}
