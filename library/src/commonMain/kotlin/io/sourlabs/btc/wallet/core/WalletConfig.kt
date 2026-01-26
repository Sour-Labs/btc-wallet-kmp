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

            return true
        }

        override fun hashCode(): Int {
            var result = seed.contentHashCode()
            result = 31 * result + purpose.hashCode()
            result = 31 * result + network.hashCode()
            result = 31 * result + account
            result = 31 * result + gapLimit
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
    ) : WalletConfig()

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
    ) : WalletConfig()

    /**
     * Whether this configuration is for a watch-only wallet.
     */
    val isWatchOnly: Boolean
        get() = this is WatchOnly
}
