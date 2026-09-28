package io.sourlabs.btc.wallet.sync

import co.touchlab.kermit.Logger
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val log = Logger.withTag("BlockchainExplorerApi")

/**
 * Retry [block] with exponential backoff and jitter on retriable failures.
 *
 * `isRetriable` defaults to "anything except [ClientRequestException]" — 4xx
 * responses indicate a request the server actively rejected, which a retry
 * won't fix. `CancellationException` is always rethrown so coroutine cancel
 * still works while a retry is in-flight.
 *
 * Intended for idempotent GETs only. `POST /tx` must remain caller-idempotent
 * so the user (or upstream code) decides what to do when a broadcast looks
 * like it failed — silent retries here could cause double-submission spam.
 *
 * `internal` for testability — see `RetryTest` in commonTest.
 */
internal suspend fun <T> withRetry(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 250,
    multiplier: Double = 3.0,
    jitterFraction: Double = 0.3,
    isRetriable: (Throwable) -> Boolean = { it !is ClientRequestException },
    block: suspend () -> T,
): T {
    require(maxAttempts > 0) { "maxAttempts must be positive" }
    var delayMs = initialDelayMs.toDouble()
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!isRetriable(e) || attempt == maxAttempts - 1) throw e
            val jitter = delayMs * jitterFraction * (Random.nextDouble() * 2 - 1)
            val sleep = (delayMs + jitter).toLong().coerceAtLeast(0)
            log.w(e) { "retriable failure on attempt ${attempt + 1}/$maxAttempts, sleeping ${sleep}ms" }
            delay(sleep)
            delayMs *= multiplier
            attempt++
        }
    }
}

/**
 * API client for blockchain data.
 *
 * Open so that focused tests (e.g. simulating network failures during a wallet
 * scan) can subclass with overridden methods rather than spin up a Ktor MockEngine.
 */
open class BlockchainExplorerApi internal constructor(
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
        log.i { "Fetching OAuth access token from ${auth.tokenUrl}" }
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
        log.d { "OAuth token fetched (expiresIn=${response.expiresIn}s)" }
        return BearerTokens(response.accessToken, response.accessToken)
    }

    /**
     * Get address information including transaction history.
     */
    open suspend fun getAddress(address: String): AddressResponse = withRetry {
        log.d { "GET /address/$address" }
        client.get("$baseUrl/address/$address").body()
    }

    /**
     * Get transactions for an address.
     */
    suspend fun getAddressTransactions(address: String): List<ApiTransaction> = withRetry {
        log.d { "GET /address/$address/txs" }
        client.get("$baseUrl/address/$address/txs").body()
    }

    /**
     * Get a page of confirmed (chain) transactions for an address. Returns up
     * to 25 newest-first per call; pass the txid of the last item from the
     * previous page as [lastSeenTxid] to fetch older entries.
     */
    suspend fun getAddressChainTxs(
        address: String,
        lastSeenTxid: String? = null
    ): List<ApiTransaction> = withRetry {
        val url = if (lastSeenTxid != null) {
            "$baseUrl/address/$address/txs/chain/$lastSeenTxid"
        } else {
            "$baseUrl/address/$address/txs/chain"
        }
        log.d { "GET /address/$address/txs/chain${if (lastSeenTxid != null) " (after=$lastSeenTxid)" else ""}" }
        client.get(url).body()
    }

    /**
     * Get all unconfirmed (mempool) transactions for an address. Returns up
     * to 50 newest-first; no pagination cursor.
     */
    suspend fun getAddressMempoolTxs(address: String): List<ApiTransaction> = withRetry {
        log.d { "GET /address/$address/txs/mempool" }
        client.get("$baseUrl/address/$address/txs/mempool").body()
    }

    /**
     * Get UTXOs for an address.
     */
    open suspend fun getAddressUtxos(address: String): List<ApiUtxo> = withRetry {
        log.d { "GET /address/$address/utxo" }
        client.get("$baseUrl/address/$address/utxo").body()
    }

    /**
     * Get a transaction by its ID.
     */
    suspend fun getTransaction(txId: String): ApiTransaction = withRetry {
        log.d { "GET /tx/$txId" }
        client.get("$baseUrl/tx/$txId").body()
    }

    /**
     * Get raw transaction hex.
     */
    suspend fun getRawTransaction(txId: String): String = withRetry {
        log.d { "GET /tx/$txId/hex" }
        client.get("$baseUrl/tx/$txId/hex").bodyAsText()
    }

    /**
     * Broadcast a transaction.
     *
     * Deliberately NOT wrapped in withRetry — broadcast must stay caller-idempotent
     * so the user (or upstream code) decides what to do when a submission looks
     * like it failed. A silent retry could double-submit and spam the explorer's
     * mempool with the same txid twice.
     *
     * @return transaction ID if successful
     */
    suspend fun broadcastTransaction(rawTxHex: String): String {
        log.d { "POST /tx (${rawTxHex.length / 2} bytes)" }
        val response = client.post("$baseUrl/tx") {
            setBody(rawTxHex)
        }
        return response.bodyAsText()
    }

    /**
     * Get current block height.
     */
    suspend fun getBlockHeight(): Int = withRetry {
        log.d { "GET /blocks/tip/height" }
        client.get("$baseUrl/blocks/tip/height").bodyAsText().toInt()
    }

    /**
     * Get block hash at height.
     */
    suspend fun getBlockHash(height: Int): String = withRetry {
        log.d { "GET /block-height/$height" }
        client.get("$baseUrl/block-height/$height").bodyAsText()
    }

    /**
     * Get block information.
     */
    suspend fun getBlock(hash: String): ApiBlock = withRetry {
        log.d { "GET /block/$hash" }
        client.get("$baseUrl/block/$hash").body()
    }

    /**
     * Get the 10 most recent blocks, or 10 blocks starting from [startHeight].
     * Returns full block metadata in a single call.
     */
    suspend fun getBlocks(startHeight: Int? = null): List<ApiBlock> = withRetry {
        val url = if (startHeight != null) "$baseUrl/blocks/$startHeight" else "$baseUrl/blocks"
        log.d { "GET /blocks${if (startHeight != null) "/$startHeight" else ""}" }
        client.get(url).body()
    }

    // Which fee endpoint this backend serves: null until the first fee request finds out. A
    // race between two first requests only costs one extra request.
    private var servesRecommendedFees: Boolean? = null

    /**
     * Get recommended fee rates from mempool.space's `/v1/fees/recommended`. A backend that
     * answers it with 404, as Esplora does (Blockstream, self-hosted electrs), is asked for
     * `/fee-estimates` instead, mapped onto the same tiers by [esploraFeeEstimates]; this
     * instance then asks that endpoint directly.
     */
    suspend fun getRecommendedFees(): FeeEstimates {
        if (servesRecommendedFees != false) {
            try {
                val fees: FeeEstimates = withRetry {
                    log.d { "GET /v1/fees/recommended" }
                    client.get("$baseUrl/v1/fees/recommended").body()
                }
                servesRecommendedFees = true
                return fees
            } catch (e: ClientRequestException) {
                if (e.response.status != HttpStatusCode.NotFound) throw e
                servesRecommendedFees = false
            }
        }
        val estimates: Map<String, Double> = withRetry {
            log.d { "GET /fee-estimates" }
            client.get("$baseUrl/fee-estimates").body()
        }
        return esploraFeeEstimates(estimates)
    }

    /**
     * Get mempool statistics.
     */
    suspend fun getMempoolInfo(): MempoolInfo = withRetry {
        log.d { "GET /mempool" }
        client.get("$baseUrl/mempool").body()
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
    // Defaulted because GET /blocks may omit this field; only /block/{hash} reliably returns it.
    val difficulty: Double = 0.0
)

@Serializable
data class FeeEstimates(
    val fastestFee: Int,
    val halfHourFee: Int,
    val hourFee: Int,
    val economyFee: Int,
    val minimumFee: Int
)

private const val MIN_RELAY_FEE_RATE = 1

/**
 * Maps Esplora's `/fee-estimates` (confirmation target in blocks to sat/vB) onto
 * [FeeEstimates] the way mempool.space's tiers read: fastest = 1 block, half hour = 3,
 * hour = 6, economy = 144, and minimum = the lowest quote. A target Esplora doesn't list
 * takes the next faster one it does list, or the fastest listed if none is. Like
 * mempool.space, a slower tier is capped at the faster one.
 *
 * Rates are rounded up to whole sat/vB, never below the 1 sat/vB minimum relay fee. Bitcoin
 * Core quotes whole sat/kvB and Esplora converts them with a floating-point multiply, so a
 * rate is first rounded to sat/kvB: `7.000000000000001` is 7, not 8.
 */
internal fun esploraFeeEstimates(estimates: Map<String, Double>): FeeEstimates {
    val byTarget = estimates.mapNotNull { (target, rate) -> target.toIntOrNull()?.let { it to rate } }.toMap()
    require(byTarget.isNotEmpty()) { "Esplora returned no fee estimates" }

    fun satPerVbyte(rate: Double): Int =
        (((rate * 1000).roundToLong() + 999) / 1000).toInt().coerceAtLeast(MIN_RELAY_FEE_RATE)

    fun rate(target: Int): Int {
        val listed = byTarget.keys.filter { it <= target }.maxOrNull() ?: byTarget.keys.min()
        return satPerVbyte(byTarget.getValue(listed))
    }

    val fastest = rate(1)
    val halfHour = minOf(rate(3), fastest)
    val hour = minOf(rate(6), halfHour)
    val economy = minOf(rate(144), hour)
    return FeeEstimates(fastest, halfHour, hour, economy, minimumFee = satPerVbyte(byTarget.values.min()))
}

@Serializable
data class MempoolInfo(
    val count: Int,
    val vsize: Long,
    @SerialName("total_fee")
    val totalFee: Long,
    @SerialName("fee_histogram")
    val feeHistogram: List<List<Double>>
)
