package io.sourlabs.btc.wallet.core

/**
 * Configuration for wallet synchronization.
 */
sealed class SyncConfig {
    /**
     * Use Mempool.space API for synchronization.
     */
    data class MempoolSpace(
        /**
         * Base URL for the Mempool.space API.
         * Default: "https://mempool.space/api" for mainnet
         */
        val baseUrl: String = DEFAULT_MAINNET_URL,

        /**
         * Polling interval in milliseconds for checking updates.
         */
        val pollingIntervalMs: Long = 30_000L
    ) : SyncConfig() {
        companion object {
            const val DEFAULT_MAINNET_URL = "https://mempool.space/api"
            const val DEFAULT_TESTNET_URL = "https://mempool.space/testnet/api"
            const val DEFAULT_SIGNET_URL = "https://mempool.space/signet/api"

            /**
             * Create a MempoolSpace config for the given network.
             */
            fun forNetwork(network: io.sourlabs.btc.wallet.models.Network): MempoolSpace {
                val url = when (network) {
                    io.sourlabs.btc.wallet.models.Network.MAINNET -> DEFAULT_MAINNET_URL
                    io.sourlabs.btc.wallet.models.Network.TESTNET -> DEFAULT_TESTNET_URL
                    io.sourlabs.btc.wallet.models.Network.SIGNET -> DEFAULT_SIGNET_URL
                    io.sourlabs.btc.wallet.models.Network.REGTEST -> DEFAULT_MAINNET_URL // User should override
                }
                return MempoolSpace(baseUrl = url)
            }
        }
    }

    data class BlockStream(
        /**
         * Base URL for the BlockStream API.
         * Default: "https://blockstream.info/api" for mainnet
         */
        val baseUrl: String = DEFAULT_MAINNET_URL,

        /**
         * Polling interval in milliseconds for checking updates.
         */
        val pollingIntervalMs: Long = 30_000L
    ) : SyncConfig() {
        companion object {
            const val DEFAULT_MAINNET_URL = "https://blockstream.info/api"
            const val DEFAULT_TESTNET_URL = "https://blockstream.info/testnet/api"
            const val DEFAULT_SIGNET_URL = "https://blockstream.info/signet/api"

            /**
             * Create a BlockStream config for the given network.
             */
            fun forNetwork(network: io.sourlabs.btc.wallet.models.Network): BlockStream {
                val url = when (network) {
                    io.sourlabs.btc.wallet.models.Network.MAINNET -> DEFAULT_MAINNET_URL
                    io.sourlabs.btc.wallet.models.Network.TESTNET -> DEFAULT_TESTNET_URL
                    io.sourlabs.btc.wallet.models.Network.SIGNET -> DEFAULT_SIGNET_URL
                    io.sourlabs.btc.wallet.models.Network.REGTEST -> DEFAULT_MAINNET_URL // User should override
                }
                return BlockStream(baseUrl = url)
            }
        }
    }

    data class MyUmbrel(
        /**
         * Base URL for my Umbrel blockchain explorer.
         * Default: "http://umbrel.tail5605a5.ts.net:3006/api" for mainnet
         */
        val baseUrl: String = DEFAULT_MAINNET_URL,

        /**
         * Polling interval in milliseconds for checking updates.
         */
        val pollingIntervalMs: Long = 30_000L
    ) : SyncConfig() {
        companion object {
            const val DEFAULT_MAINNET_URL = "http://umbrel.tail5605a5.ts.net:3006/api"
            const val DEFAULT_TESTNET_URL = "http://umbrel.tail5605a5.ts.net:3006/testnet/api"
            const val DEFAULT_SIGNET_URL = "http://umbrel.tail5605a5.ts.net:3006/signet/api"

            /**
             * Create a MyUmbrel config for the given network.
             */
            fun forNetwork(network: io.sourlabs.btc.wallet.models.Network): MyUmbrel {
                val url = when (network) {
                    io.sourlabs.btc.wallet.models.Network.MAINNET -> DEFAULT_MAINNET_URL
                    io.sourlabs.btc.wallet.models.Network.TESTNET -> DEFAULT_TESTNET_URL
                    io.sourlabs.btc.wallet.models.Network.SIGNET -> DEFAULT_SIGNET_URL
                    io.sourlabs.btc.wallet.models.Network.REGTEST -> DEFAULT_MAINNET_URL // User should override
                }
                return MyUmbrel(baseUrl = url)
            }
        }
    }

    /**
     * Custom API provider with a user-provided base URL.
     */
    data class CustomApi(
        val baseUrl: String,
        val pollingIntervalMs: Long = 30_000L
    ) : SyncConfig()
}
