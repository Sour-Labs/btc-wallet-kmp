package io.sourlabs.btc.wallet.core

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
     * Create a wallet from an extended private key (xprv/yprv/zprv/tprv).
     */
    data class FromExtendedPrivateKey(
        val extendedKey: String,
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
     * Create a watch-only wallet from a BIP-380 output descriptor, e.g.
     * `wpkh(...)` wrapping an account-level extended public key with a
     * `#xxxxxxxx` checksum. Both [purpose] and [network] are dictated by the
     * descriptor itself — the wrapper picks the BIP, and the embedded extended
     * key's SLIP-132 prefix picks the network. [account] is taken from the
     * third path step of the key origin if present, else 0.
     *
     * See [io.sourlabs.btc.wallet.descriptors.Descriptor] for the supported
     * subset.
     */
    data class WatchOnlyDescriptor(
        val descriptor: String,
        override val gapLimit: Int = 20,
        override val confirmationsThreshold: Int = 1,
    ) : WalletConfig() {
        private val parsed: io.sourlabs.btc.wallet.descriptors.Descriptor =
            io.sourlabs.btc.wallet.descriptors.Descriptor.parse(descriptor)

        override val purpose: Purpose = parsed.purpose
        override val network: Network = parsed.network
        override val account: Int = parsed.account

        /** The parsed descriptor — exposed for downstream consumers. */
        val parsedDescriptor: io.sourlabs.btc.wallet.descriptors.Descriptor get() = parsed

        init {
            require(gapLimit > 0) { "Gap limit must be positive" }
        }
    }

    /**
     * Whether this configuration is for a watch-only wallet.
     */
    val isWatchOnly: Boolean
        get() = this is WatchOnly || this is WatchOnlyDescriptor
}
