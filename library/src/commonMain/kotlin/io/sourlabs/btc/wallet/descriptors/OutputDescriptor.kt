package io.sourlabs.btc.wallet.descriptors

import io.sourlabs.btc.wallet.api.DescriptorException
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose

/**
 * Umbrella sum type for the two flavours of BIP-380 output descriptor this
 * library understands: single-key ([Descriptor]) and multisig
 * ([MultisigDescriptor]). Use [parse] as the entry point when you don't know
 * which flavour the input is — most often the case in wallet-import UIs that
 * accept a paste of "whatever the user has."
 *
 * For callers that already know the shape, [Descriptor.parse] and
 * [MultisigDescriptor.parse] remain available and throw
 * [DescriptorException.Unsupported] when the input is the wrong flavour.
 */
sealed class OutputDescriptor {
    /** BIP purpose dictated by the descriptor wrapper. */
    abstract val purpose: Purpose

    /** Network inferred from the embedded extended public keys. */
    abstract val network: Network

    data class SingleKey(val descriptor: Descriptor) : OutputDescriptor() {
        override val purpose: Purpose = descriptor.purpose
        override val network: Network = descriptor.network
    }

    data class Multisig(val descriptor: MultisigDescriptor) : OutputDescriptor() {
        override val purpose: Purpose = descriptor.purpose
        override val network: Network = descriptor.network
    }

    companion object {
        /**
         * Parse any supported output descriptor. Dispatches to
         * [Descriptor.parse] or [MultisigDescriptor.parse] based on the
         * top-level wrapper.
         */
        fun parse(input: String): OutputDescriptor {
            val trimmed = input.trim()
            // Peek at the top-level wrapper *before* checksum verification so
            // we can route to the correct parser. The dispatched parser then
            // re-verifies the checksum (small duplicate work, kept for code
            // clarity — descriptor parsing is not a hot path).
            val openIdx = trimmed.indexOf('(')
            if (openIdx <= 0) {
                throw DescriptorException.Malformed(
                    "expected a descriptor of the form wrapper(...), got '$trimmed'"
                )
            }
            // The trimmed input still has the #checksum suffix; we only need
            // the wrapper name, which sits before the first '('.
            val wrapper = trimmed.substring(0, openIdx)
            return when (wrapper) {
                "wsh" -> Multisig(MultisigDescriptor.parse(trimmed))
                "pkh", "wpkh", "sh", "tr" -> SingleKey(Descriptor.parse(trimmed))
                else -> throw DescriptorException.Unsupported(
                    "unknown descriptor function: $wrapper()"
                )
            }
        }
    }
}
