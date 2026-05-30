package io.sourlabs.btc.wallet.core

import io.sourlabs.btc.wallet.descriptors.Descriptor
import io.sourlabs.btc.wallet.descriptors.OutputDescriptor
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose

/**
 * Configuration for wallet initialization.
 */
sealed class WalletConfig {
    /**
     * Network for this wallet.
     */
    abstract val network: Network

    /**
     * BIP purpose for address derivation.
     */
    abstract val purpose: Purpose

    /**
     * Account index (default 0).
     */
    abstract val account: Int

    /**
     * Gap limit for address discovery (default 20).
     */
    abstract val gapLimit: Int

    /**
     * Minimum confirmations required for incoming transactions to be spendable.
     */
    abstract val confirmationsThreshold: Int

    /**
     * Create a wallet from BIP39 mnemonic words.
     */
    data class FromMnemonic(
        val mnemonic: List<String>,
        val passphrase: String = "",
        override val purpose: Purpose = Purpose.BIP84,
        override val network: Network = Network.MAINNET,
        override val account: Int = 0,
        override val gapLimit: Int = 20,
        override val confirmationsThreshold: Int = 1
    ) : WalletConfig() {
        init {
            require(mnemonic.size in listOf(12, 15, 18, 21, 24)) {
                "Mnemonic must be 12, 15, 18, 21, or 24 words"
            }
            require(gapLimit > 0) { "Gap limit must be positive" }
        }
    }

    /**
     * Create a wallet from raw seed bytes.
     *
     * **Security note:** [seed] is retained on the heap as a normal `ByteArray`
     * for the lifetime of this config — Kotlin Multiplatform doesn't expose a
     * cross-platform primitive for locked / zeroed memory. Callers handling
     * sensitive seed material should clear their copy after the wallet is
     * built (`seed.fill(0)`) and avoid retaining the [FromSeed] instance any
     * longer than needed.
     */
    data class FromSeed(
        val seed: ByteArray,
        override val purpose: Purpose = Purpose.BIP84,
        override val network: Network = Network.MAINNET,
        override val account: Int = 0,
        override val gapLimit: Int = 20,
        override val confirmationsThreshold: Int = 1
    ) : WalletConfig() {
        init {
            require(seed.size >= 16) { "Seed must be at least 16 bytes" }
            require(gapLimit > 0) { "Gap limit must be positive" }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as FromSeed

            if (!seed.contentEquals(other.seed)) return false
            if (purpose != other.purpose) return false
            if (network != other.network) return false
            if (account != other.account) return false
            if (gapLimit != other.gapLimit) return false
            if (confirmationsThreshold != other.confirmationsThreshold) return false

            return true
        }

        override fun hashCode(): Int {
            var result = seed.contentHashCode()
            result = 31 * result + purpose.hashCode()
            result = 31 * result + network.hashCode()
            result = 31 * result + account
            result = 31 * result + gapLimit
            result = 31 * result + confirmationsThreshold
            return result
        }
    }

    /**
     * Create a wallet from an *account-level* extended private key — a BIP-32
     * key at `m/purpose'/coin'/account'` (depth 3), e.g. `xprv9z...` exported
     * by a hardware wallet for the account you want this kit to manage.
     *
     * Master xprv keys (depth 0) are rejected at construction time. If you
     * only have the master xprv, derive the account key yourself first via
     * `DeterministicWallet.derivePrivateKey(master, "m/84'/0'/0'")`.
     */
    data class FromExtendedPrivateKey(
        val accountExtendedPrivateKey: String,
        override val purpose: Purpose = Purpose.BIP84,
        override val network: Network = Network.MAINNET,
        override val account: Int = 0,
        override val gapLimit: Int = 20,
        override val confirmationsThreshold: Int = 1
    ) : WalletConfig() {
        init {
            require(gapLimit > 0) { "Gap limit must be positive" }
        }
    }

    /**
     * Create a watch-only wallet from an extended public key (xpub/ypub/zpub/tpub).
     */
    data class WatchOnly(
        val extendedPublicKey: String,
        override val purpose: Purpose = Purpose.BIP84,
        override val network: Network = Network.MAINNET,
        override val account: Int = 0,
        override val gapLimit: Int = 20,
        override val confirmationsThreshold: Int = 1
    ) : WalletConfig() {
        init {
            require(gapLimit > 0) { "Gap limit must be positive" }
        }
    }

    /**
     * Create a watch-only wallet from a BIP-380 output descriptor.
     *
     * Accepts both single-key descriptors (`wpkh(...)`, `pkh(...)`, `sh(wpkh(...))`,
     * `tr(...)`) and multisig descriptors (`wsh(sortedmulti(M, KEY1, ..., KEYN))`).
     * [purpose] and [network] are dictated by the descriptor itself — the
     * wrapper picks the BIP and the embedded extended keys' SLIP-132 prefix
     * picks the network. For single-key descriptors [account] is the third
     * path step of the key origin if present, else 0; multisig descriptors
     * don't have a single account and report 0.
     *
     * See [OutputDescriptor] for the supported subset.
     */
    data class WatchOnlyDescriptor(
        val descriptor: String,
        override val gapLimit: Int = 20,
        override val confirmationsThreshold: Int = 1,
    ) : WalletConfig() {
        // Initialized in init {} so any DescriptorException surfaces from a
        // clearly-construction-time block, not from a property-initializer
        // chain that's harder to read in a stack trace.
        private val parsed: OutputDescriptor
        override val purpose: Purpose
        override val network: Network
        override val account: Int

        /**
         * The parsed descriptor as the unified sum type — exposes both the
         * single-key and multisig flavours so the builder can dispatch on it.
         */
        val parsedOutputDescriptor: OutputDescriptor get() = parsed

        /**
         * Convenience accessor for single-key descriptors. Throws
         * [IllegalStateException] if the descriptor is multisig — callers that
         * need to handle both shapes should read [parsedOutputDescriptor]
         * instead. Kept for source-compatibility with the previous
         * single-key-only API.
         */
        val parsedDescriptor: Descriptor
            get() = when (val p = parsed) {
                is OutputDescriptor.SingleKey -> p.descriptor
                is OutputDescriptor.Multisig -> error(
                    "parsedDescriptor is only valid for single-key descriptors; " +
                        "use parsedOutputDescriptor for multisig support"
                )
            }

        init {
            require(gapLimit > 0) { "Gap limit must be positive" }
            parsed = OutputDescriptor.parse(descriptor)
            purpose = parsed.purpose
            network = parsed.network
            account = when (val p = parsed) {
                is OutputDescriptor.SingleKey -> p.descriptor.account
                is OutputDescriptor.Multisig -> p.descriptor.account
            }
        }
    }

    /**
     * Whether this configuration is for a watch-only wallet.
     */
    val isWatchOnly: Boolean
        get() = this is WatchOnly || this is WatchOnlyDescriptor
}
