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
            const val DEFAULT_TESTNET4_URL = "https://mempool.space/testnet4/api"
            const val DEFAULT_SIGNET_URL = "https://mempool.space/signet/api"

            /**
             * Create a MempoolSpace config for the given network.
             *
             * REGTEST has no public endpoint — pass the regtest baseUrl explicitly
             * via the constructor instead.
             */
            fun forNetwork(network: io.sourlabs.btc.wallet.models.Network): MempoolSpace {
                val url = when (network) {
                    io.sourlabs.btc.wallet.models.Network.MAINNET -> DEFAULT_MAINNET_URL
                    io.sourlabs.btc.wallet.models.Network.TESTNET -> DEFAULT_TESTNET_URL
                    io.sourlabs.btc.wallet.models.Network.TESTNET4 -> DEFAULT_TESTNET4_URL
                    io.sourlabs.btc.wallet.models.Network.SIGNET -> DEFAULT_SIGNET_URL
                    io.sourlabs.btc.wallet.models.Network.REGTEST -> throw IllegalArgumentException(
                        "REGTEST has no public Mempool.space endpoint; " +
                            "construct SyncConfig.MempoolSpace(baseUrl = ...) explicitly"
                    )
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
        val pollingIntervalMs: Long = 30_000L,

        /**
         * OAuth2 credentials for the Blockstream Explorer Enterprise API. When set, requests
         * attach a Bearer access token obtained via the client_credentials flow.
         * Null means use the free, unauthenticated tier.
         */
        val auth: Auth? = null
    ) : SyncConfig() {
        /**
         * Credentials for the Blockstream Explorer Enterprise API. The secret must never be
         * embedded in a distributed client binary — fetch it from a server you control or
         * have the end user provide it. See README for guidance.
         */
        data class Auth(
            val clientId: String,
            val clientSecret: String,
            val tokenUrl: String = DEFAULT_TOKEN_URL
        ) {
            // Prevent the data-class-generated toString() from leaking clientSecret into logs
            // or crash reports when a BlockStream config is printed.
            override fun toString(): String =
                "Auth(clientId=$clientId, clientSecret=***, tokenUrl=$tokenUrl)"

            companion object {
                const val DEFAULT_TOKEN_URL =
                    "https://login.blockstream.com/realms/blockstream-public/protocol/openid-connect/token"
            }
        }

        companion object {
            const val DEFAULT_MAINNET_URL = "https://blockstream.info/api"
            const val DEFAULT_TESTNET_URL = "https://blockstream.info/testnet/api"
            const val DEFAULT_SIGNET_URL = "https://blockstream.info/signet/api"

            const val ENTERPRISE_MAINNET_URL = "https://enterprise.blockstream.info/api"
            const val ENTERPRISE_TESTNET_URL = "https://enterprise.blockstream.info/testnet/api"

            /**
             * Create a BlockStream config for the given network.
             *
             * REGTEST has no public endpoint, and Blockstream's explorer does not
             * currently serve Testnet4 — for TESTNET4 use
             * [SyncConfig.MempoolSpace.forNetwork] or pass an explicit baseUrl via
             * the constructor. Same for REGTEST.
             */
            fun forNetwork(network: io.sourlabs.btc.wallet.models.Network): BlockStream {
                val url = when (network) {
                    io.sourlabs.btc.wallet.models.Network.MAINNET -> DEFAULT_MAINNET_URL
                    io.sourlabs.btc.wallet.models.Network.TESTNET -> DEFAULT_TESTNET_URL
                    io.sourlabs.btc.wallet.models.Network.SIGNET -> DEFAULT_SIGNET_URL
                    io.sourlabs.btc.wallet.models.Network.TESTNET4 -> throw IllegalArgumentException(
                        "Blockstream does not currently serve Testnet4; use " +
                            "SyncConfig.MempoolSpace.forNetwork(Network.TESTNET4) " +
                            "or construct SyncConfig.BlockStream(baseUrl = ...) explicitly"
                    )
                    io.sourlabs.btc.wallet.models.Network.REGTEST -> throw IllegalArgumentException(
                        "REGTEST has no public Blockstream endpoint; " +
                            "construct SyncConfig.BlockStream(baseUrl = ...) explicitly"
                    )
                }
                return BlockStream(baseUrl = url)
            }

            /**
             * Create a BlockStream config that hits the authenticated Enterprise tier using
             * OAuth2 client_credentials. Enterprise only serves mainnet and testnet — SIGNET
             * falls back to the free public endpoint with no auth, Testnet4 and REGTEST have
             * no Blockstream endpoint at all (use [SyncConfig.MempoolSpace] for Testnet4, or
             * construct SyncConfig.BlockStream(baseUrl = ...) explicitly for either).
             */
            fun enterprise(
                network: io.sourlabs.btc.wallet.models.Network,
                clientId: String,
                clientSecret: String,
                tokenUrl: String = Auth.DEFAULT_TOKEN_URL
            ): BlockStream = when (network) {
                io.sourlabs.btc.wallet.models.Network.MAINNET -> BlockStream(
                    baseUrl = ENTERPRISE_MAINNET_URL,
                    auth = Auth(clientId, clientSecret, tokenUrl)
                )
                io.sourlabs.btc.wallet.models.Network.TESTNET -> BlockStream(
                    baseUrl = ENTERPRISE_TESTNET_URL,
                    auth = Auth(clientId, clientSecret, tokenUrl)
                )
                io.sourlabs.btc.wallet.models.Network.SIGNET -> BlockStream(baseUrl = DEFAULT_SIGNET_URL)
                io.sourlabs.btc.wallet.models.Network.TESTNET4 -> throw IllegalArgumentException(
                    "Blockstream does not serve Testnet4 (Enterprise or otherwise); use " +
                        "SyncConfig.MempoolSpace.forNetwork(Network.TESTNET4) or " +
                        "construct SyncConfig.BlockStream(baseUrl = ...) explicitly"
                )
                io.sourlabs.btc.wallet.models.Network.REGTEST -> throw IllegalArgumentException(
                    "REGTEST has no public Blockstream endpoint; " +
                        "construct SyncConfig.BlockStream(baseUrl = ...) explicitly"
                )
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
