package io.sourlabs.btc.wallet.models

import fr.acinq.bitcoin.Chain

/**
 * Bitcoin network types.
 */
enum class Network {
    MAINNET,
    TESTNET,
    /**
     * Bitcoin Testnet4, the post-2024 replacement for the long-broken Testnet3
     * (Testnet3 has serious mining/spam issues that violate the 20-minute
     * difficulty rule and have made faucets unreliable).
     *
     * Shares BIP-44 coin type 1 with [TESTNET], so HD derivation paths are
     * identical — only the underlying chain differs.
     */
    TESTNET4,
    SIGNET,
    REGTEST;

    /**
     * BIP44 coin type for this network.
     * 0 for mainnet, 1 for testnet variants (Testnet3, Testnet4, Signet, Regtest).
     */
    val coinType: Int
        get() = when (this) {
            MAINNET -> 0
            TESTNET, TESTNET4, SIGNET, REGTEST -> 1
        }

    /**
     * Human-readable name.
     */
    val displayName: String
        get() = when (this) {
            MAINNET -> "Bitcoin Mainnet"
            TESTNET -> "Bitcoin Testnet"
            TESTNET4 -> "Bitcoin Testnet4"
            SIGNET -> "Bitcoin Signet"
            REGTEST -> "Bitcoin Regtest"
        }

    /**
     * Convert to ACINQ bitcoin-kmp Chain.
     */
    fun toChain(): Chain = when (this) {
        MAINNET -> Chain.Mainnet
        TESTNET -> Chain.Testnet3
        TESTNET4 -> Chain.Testnet4
        SIGNET -> Chain.Signet
        REGTEST -> Chain.Regtest
    }

    companion object {
        fun fromChain(chain: Chain): Network = when (chain) {
            Chain.Mainnet -> MAINNET
            Chain.Testnet3 -> TESTNET
            Chain.Testnet4 -> TESTNET4
            Chain.Signet -> SIGNET
            Chain.Regtest -> REGTEST
        }
    }
}
