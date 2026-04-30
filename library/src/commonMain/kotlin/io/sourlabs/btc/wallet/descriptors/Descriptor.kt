package io.sourlabs.btc.wallet.descriptors

import io.sourlabs.btc.wallet.api.DescriptorException
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose

/**
 * A parsed BIP-380 output descriptor, restricted to the single-key, single-script-type
 * subset used by watch-only hardware wallet imports:
 *
 *  - `pkh(KEY)` — legacy P2PKH (BIP-44)
 *  - `sh(wpkh(KEY))` — wrapped SegWit (BIP-49)
 *  - `wpkh(KEY)` — native SegWit (BIP-84)
 *  - `tr(KEY)` — taproot key-path only (BIP-86)
 *
 * Multi-sig (`multi`, `sortedmulti`, `wsh`), miniscript, MuSig2, and `tr(KEY, TREE)`
 * with a script tree are explicitly rejected with [DescriptorException.Unsupported].
 *
 * The embedded key must be an extended public key (xpub/ypub/zpub or
 * tpub/upub/vpub). Raw hex pubkeys, WIF private keys, and xprv keys are not
 * supported in v1.
 */
sealed class Descriptor {

    /** Optional `[fingerprint/path]` key origin info preceding the key. */
    abstract val keyOrigin: KeyOrigin?

    /** The embedded extended public key, exactly as it appeared in the descriptor. */
    abstract val extendedPublicKey: String

    /** Network inferred from the [extendedPublicKey] SLIP-132 prefix. */
    abstract val network: Network

    /** BIP purpose dictated by the script wrapper. */
    abstract val purpose: Purpose

    data class Pkh(
        override val keyOrigin: KeyOrigin?,
        override val extendedPublicKey: String,
        override val network: Network,
    ) : Descriptor() {
        override val purpose: Purpose = Purpose.BIP44
    }

    data class ShWpkh(
        override val keyOrigin: KeyOrigin?,
        override val extendedPublicKey: String,
        override val network: Network,
    ) : Descriptor() {
        override val purpose: Purpose = Purpose.BIP49
    }

    data class Wpkh(
        override val keyOrigin: KeyOrigin?,
        override val extendedPublicKey: String,
        override val network: Network,
    ) : Descriptor() {
        override val purpose: Purpose = Purpose.BIP84
    }

    data class Tr(
        override val keyOrigin: KeyOrigin?,
        override val extendedPublicKey: String,
        override val network: Network,
    ) : Descriptor() {
        override val purpose: Purpose = Purpose.BIP86
    }

    /**
     * The account index implied by the key origin, if a third path step is
     * present (typical layout: `purpose'/coin'/account'`). Falls back to 0 when
     * the descriptor lacks origin info or the path is shorter than 3 steps.
     */
    val account: Int
        get() = keyOrigin?.path?.getOrNull(2)?.indexUnhardened()?.toInt() ?: 0

    /**
     * One step of a derivation path inside a key origin.
     */
    data class PathStep(val index: Long, val hardened: Boolean) {
        /** The unhardened index, i.e. with any hardening offset removed. */
        fun indexUnhardened(): Long = index

        override fun toString(): String = if (hardened) "${index}h" else index.toString()
    }

    /**
     * The `[fingerprint/path]` origin preamble. The fingerprint is 4 bytes (8
     * hex characters); the path is a possibly-empty sequence of derivation
     * steps. Either `'` or `h` is accepted for hardened steps.
     */
    data class KeyOrigin(
        val fingerprint: ByteArray,
        val path: List<PathStep>,
    ) {
        init {
            require(fingerprint.size == 4) { "fingerprint must be 4 bytes" }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as KeyOrigin
            return fingerprint.contentEquals(other.fingerprint) && path == other.path
        }

        override fun hashCode(): Int = 31 * fingerprint.contentHashCode() + path.hashCode()

        override fun toString(): String {
            val fp = fingerprint.joinToString("") {
                val hex = (it.toInt() and 0xff).toString(16)
                if (hex.length == 1) "0$hex" else hex
            }
            return if (path.isEmpty()) "[$fp]" else "[$fp/${path.joinToString("/")}]"
        }
    }

    companion object {
        /**
         * Parse a descriptor string. Requires the trailing `#xxxxxxxx` checksum
         * (BIP-380); descriptors without one are rejected.
         *
         * @throws DescriptorException.InvalidChecksum if the checksum is missing or wrong
         * @throws DescriptorException.Malformed if the body's grammar is invalid
         * @throws DescriptorException.Unsupported if the wrapper, key type, or
         *   derivation suffix isn't in the supported subset
         */
        fun parse(input: String): Descriptor = DescriptorParser.parse(input.trim())
    }
}
