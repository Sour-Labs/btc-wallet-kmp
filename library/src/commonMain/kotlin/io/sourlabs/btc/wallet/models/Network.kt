package io.sourlabs.btc.wallet.models

import fr.acinq.bitcoin.Chain

/**
 * Bitcoin network types.
 */
enum class Network {
    MAINNET,
    TESTNET,
    SIGNET,
    REGTEST;

    /**
     * BIP44 coin type for this network.
     * 0 for mainnet, 1 for testnet/signet/regtest.
     */
    val coinType: Int
        get() = when (this) {
            MAINNET -> 0
            TESTNET, SIGNET, REGTEST -> 1
        }

    /**
     * Human-readable name.
     */
    val displayName: String
        get() = when (this) {
            MAINNET -> "Bitcoin Mainnet"
            TESTNET -> "Bitcoin Testnet"
            SIGNET -> "Bitcoin Signet"
            REGTEST -> "Bitcoin Regtest"
        }

    /**
     * Convert to ACINQ bitcoin-kmp Chain.
     */
    fun toChain(): Chain = when (this) {
        MAINNET -> Chain.Mainnet
        TESTNET -> Chain.Testnet3
        SIGNET -> Chain.Signet
        REGTEST -> Chain.Regtest
    }

    companion object {
        fun fromChain(chain: Chain): Network = when (chain) {
            Chain.Mainnet -> MAINNET
            Chain.Testnet3 -> TESTNET
            Chain.Testnet4 -> TESTNET // Map Testnet4 to TESTNET as well
            Chain.Signet -> SIGNET
            Chain.Regtest -> REGTEST
        }
    }
}
