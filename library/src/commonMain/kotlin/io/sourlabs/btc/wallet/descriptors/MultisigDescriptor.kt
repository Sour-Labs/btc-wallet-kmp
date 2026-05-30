package io.sourlabs.btc.wallet.descriptors

import io.sourlabs.btc.wallet.api.DescriptorException
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose

/**
 * A parsed BIP-380 multisig output descriptor.
 *
 * Currently supports `wsh(sortedmulti(M, KEY1, ..., KEYN))` — the format Bitkey,
 * Sparrow, BlueWallet, and most modern M-of-N watch-only setups emit. Each key
 * is an account-level xpub with an optional `[fingerprint/path]` key-origin
 * preamble and a shared derivation suffix (the receive-only or multipath
 * form). All keys must share the same network and suffix.
 *
 * Single-key descriptors (`pkh`, `wpkh`, `sh(wpkh)`, `tr`) are modelled
 * separately in [Descriptor]. [OutputDescriptor] is the umbrella entry point
 * that returns either, depending on the wrapper.
 */
sealed class MultisigDescriptor {
    /** Number of signatures required to spend (M in M-of-N). */
    abstract val threshold: Int

    /**
     * Cosigner keys in the order they appeared in the descriptor. For
     * [sorted] descriptors the runtime address deriver will sort the derived
     * child pubkeys lexicographically before building the redeem script (BIP-67);
     * this list preserves the human-readable order for display purposes.
     */
    abstract val keys: List<Key>

    /**
     * `true` when the descriptor's inner function is `sortedmulti` — pubkeys
     * are sorted per BIP-67 before building the redeem script. `false` for
     * plain `multi(...)` where the input order is preserved (currently rejected
     * by the parser as none of the target wallets emit it).
     */
    abstract val sorted: Boolean

    /** Network inferred from the cosigner xpubs (all must agree). */
    abstract val network: Network

    /**
     * BIP purpose hint for downstream code. P2WSH multisig is native segwit,
     * so we surface BIP-84 — the closest single-key analogue — even though
     * multisig wallets don't strictly follow the BIP-44 purpose semantics.
     */
    val purpose: Purpose get() = Purpose.BIP84

    /**
     * Account index. Multisig descriptors have one path per cosigner so there
     * is no single account number; we surface 0 to match the [Descriptor]
     * interface shape. Callers that need per-cosigner accounts should inspect
     * [Key.keyOrigin] directly.
     */
    val account: Int get() = 0

    /**
     * `wsh(sortedmulti(M, KEY...))` — keys are sorted lexicographically per
     * BIP-67 before the redeem script is built. The canonical script type
     * is native-segwit P2WSH.
     */
    data class WshSortedMulti(
        override val threshold: Int,
        override val keys: List<Key>,
        override val network: Network,
    ) : MultisigDescriptor() {
        override val sorted: Boolean = true
    }

    /**
     * One cosigner inside a multisig descriptor — an extended public key with
     * an optional `[fingerprint/path]` key origin. The derivation suffix
     * (receive-only or multipath form) is shared across all keys in the
     * descriptor and stored on the descriptor itself, not here.
     */
    data class Key(
        val keyOrigin: Descriptor.KeyOrigin?,
        val extendedPublicKey: String,
    )

    companion object {
        /**
         * Parse a multisig descriptor string with the trailing `#xxxxxxxx`
         * BIP-380 checksum.
         *
         * @throws DescriptorException.InvalidChecksum if the checksum is missing or wrong
         * @throws DescriptorException.Malformed if the body's grammar is invalid
         * @throws DescriptorException.Unsupported if the wrapper or inner function
         *   isn't a supported multisig form (only `wsh(sortedmulti(...))` for now)
         */
        fun parse(input: String): MultisigDescriptor = MultisigDescriptorParser.parse(input.trim())
    }
}
