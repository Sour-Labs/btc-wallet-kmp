package io.sourlabs.btc.wallet.sync

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.sourlabs.btc.wallet.core.SyncConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * API client for blockchain data.
 */
class BlockchainExplorerApi private constructor(
    private val baseUrl: String,
    private val auth: SyncConfig.BlockStream.Auth?,
    httpClient: HttpClient?
) {
    constructor(baseUrl: String, httpClient: HttpClient? = null) : this(baseUrl, null, httpClient)

    constructor(config: SyncConfig.BlockStream, httpClient: HttpClient? = null) :
        this(config.baseUrl, config.auth, httpClient)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Separate client for the token endpoint so the auth plugin doesn't recurse into itself
    // when fetching/refreshing a token. Only created when auth is configured.
    private val tokenClient: HttpClient? = auth?.let {
        HttpClient {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
            }
            expectSuccess = true
        }
    }

    private val client = httpClient ?: HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
        if (auth != null) {
            install(Auth) {
                bearer {
                    loadTokens { fetchToken(auth) }
                    refreshTokens { fetchToken(auth) }
                    sendWithoutRequest { true }
                }
            }
        }
        // Throw ClientRequestException/ServerResponseException on non-2xx. Without
        // this, bodyAsText() silently returns error bodies as data — e.g. blockstream
        // returning 400 "Block not found" from /block-height/{h} during CDN lag is
        // then passed as a hash into /block/{hash}, producing a confusing
        // deserialization failure downstream.
        expectSuccess = true
    }

    private suspend fun fetchToken(auth: SyncConfig.BlockStream.Auth): BearerTokens {
        val response: TokenResponse = tokenClient!!.submitForm(
            url = auth.tokenUrl,
            formParameters = Parameters.build {
                append("client_id", auth.clientId)
                append("client_secret", auth.clientSecret)
                append("grant_type", "client_credentials")
                append("scope", "openid")
            }
        ).body()
        // Enterprise tokens have no refresh token (refresh_expires_in = 0); re-running
        // client_credentials in refreshTokens{} is how we renew. BearerTokens requires a
        // non-null refresh value, so pass the access token itself — it's never actually used.
        return BearerTokens(response.accessToken, response.accessToken)
    }

    /**
     * Get address information including transaction history.
     */
    suspend fun getAddress(address: String): AddressResponse {
        return client.get("$baseUrl/address/$address").body()
    }

    /**
     * Get transactions for an address.
     */
    suspend fun getAddressTransactions(address: String): List<ApiTransaction> {
        return client.get("$baseUrl/address/$address/txs").body()
    }

    /**
     * Get UTXOs for an address.
     */
    suspend fun getAddressUtxos(address: String): List<ApiUtxo> {
        return client.get("$baseUrl/address/$address/utxo").body()
    }

    /**
     * Get a transaction by its ID.
     */
    suspend fun getTransaction(txId: String): ApiTransaction {
        return client.get("$baseUrl/tx/$txId").body()
    }

    /**
     * Get raw transaction hex.
     */
    suspend fun getRawTransaction(txId: String): String {
        return client.get("$baseUrl/tx/$txId/hex").bodyAsText()
    }

    /**
     * Broadcast a transaction.
     * @return transaction ID if successful
     */
    suspend fun broadcastTransaction(rawTxHex: String): String {
        val response = client.post("$baseUrl/tx") {
            setBody(rawTxHex)
        }
        return response.bodyAsText()
    }

    /**
     * Get current block height.
     */
    suspend fun getBlockHeight(): Int {
        return client.get("$baseUrl/blocks/tip/height").bodyAsText().toInt()
    }

    /**
     * Get block hash at height.
     */
    suspend fun getBlockHash(height: Int): String {
        return client.get("$baseUrl/block-height/$height").bodyAsText()
    }

    /**
     * Get block information.
     */
    suspend fun getBlock(hash: String): ApiBlock {
        return client.get("$baseUrl/block/$hash").body()
    }

    /**
     * Get recommended fee rates.
     */
    suspend fun getRecommendedFees(): FeeEstimates {
        return client.get("$baseUrl/v1/fees/recommended").body()
    }

    /**
     * Get mempool statistics.
     */
    suspend fun getMempoolInfo(): MempoolInfo {
        return client.get("$baseUrl/mempool").body()
    }

    fun close() {
        client.close()
        tokenClient?.close()
    }
}

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Int = 0,
    @SerialName("token_type") val tokenType: String = "Bearer"
)

// API Response Models

@Serializable
data class AddressResponse(
    val address: String,
    @SerialName("chain_stats")
    val chainStats: AddressStats,
    @SerialName("mempool_stats")
    val mempoolStats: AddressStats
)

@Serializable
data class AddressStats(
    @SerialName("funded_txo_count")
    val fundedTxoCount: Int,
    @SerialName("funded_txo_sum")
    val fundedTxoSum: Long,
    @SerialName("spent_txo_count")
    val spentTxoCount: Int,
    @SerialName("spent_txo_sum")
    val spentTxoSum: Long,
    @SerialName("tx_count")
    val txCount: Int
)

@Serializable
data class ApiTransaction(
    val txid: String,
    val version: Int,
    val locktime: Long,
    val vin: List<ApiVin>,
    val vout: List<ApiVout>,
    val size: Int,
    val weight: Int,
    val fee: Long,
    val status: ApiTxStatus
)

@Serializable
data class ApiVin(
    val txid: String? = null,
    val vout: Int? = null,
    val prevout: ApiVout? = null,
    @SerialName("scriptsig")
    val scriptSig: String = "",
    @SerialName("scriptsig_asm")
    val scriptSigAsm: String = "",
    val witness: List<String>? = null,
    @SerialName("is_coinbase")
    val isCoinbase: Boolean = false,
    val sequence: Long = 0xFFFFFFFF
)

@Serializable
data class ApiVout(
    @SerialName("scriptpubkey")
    val scriptPubKey: String,
    @SerialName("scriptpubkey_asm")
    val scriptPubKeyAsm: String = "",
    @SerialName("scriptpubkey_type")
    val scriptPubKeyType: String = "",
    @SerialName("scriptpubkey_address")
    val scriptPubKeyAddress: String? = null,
    val value: Long
)

@Serializable
data class ApiTxStatus(
    val confirmed: Boolean,
    @SerialName("block_height")
    val blockHeight: Int? = null,
    @SerialName("block_hash")
    val blockHash: String? = null,
    @SerialName("block_time")
    val blockTime: Long? = null
)

@Serializable
data class ApiUtxo(
    val txid: String,
    val vout: Int,
    val status: ApiTxStatus,
    val value: Long
)

@Serializable
data class ApiBlock(
    val id: String,
    val height: Int,
    val version: Int,
    val timestamp: Long,
    @SerialName("tx_count")
    val txCount: Int,
    val size: Int,
    val weight: Int,
    @SerialName("merkle_root")
    val merkleRoot: String,
    val previousblockhash: String? = null,
    val mediantime: Long,
    val nonce: Long,
    val bits: Long,
    val difficulty: Double
)

@Serializable
data class FeeEstimates(
    val fastestFee: Int,
    val halfHourFee: Int,
    val hourFee: Int,
    val economyFee: Int,
    val minimumFee: Int
)

@Serializable
data class MempoolInfo(
    val count: Int,
    val vsize: Long,
    @SerialName("total_fee")
    val totalFee: Long,
    @SerialName("fee_histogram")
    val feeHistogram: List<List<Double>>
)
