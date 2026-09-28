package io.sourlabs.btc.wallet.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.sourlabs.btc.wallet.core.SyncConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val NOT_FOUND = "endpoint does not exist"

class RecommendedFeesEndpointTest {

    private val recommended = "/api/v1/fees/recommended"
    private val feeEstimates = "/api/fee-estimates"
    private val recommendedJson = """{"fastestFee":3,"halfHourFee":2,"hourFee":1,"economyFee":1,"minimumFee":1}"""
    private val feeEstimatesJson = """{"1":3.11,"3":2.11,"6":2.11,"144":0.28}"""
    private val mapped = FeeEstimates(fastestFee = 4, halfHourFee = 3, hourFee = 3, economyFee = 1, minimumFee = 1)

    private val requested = mutableListOf<String>()

    /**
     * An explorer whose [answer] gives (status, JSON body) for a path and the number of times
     * that path was asked before. Built like SyncManager builds it: from a BlockStream config
     * when [blockstream], else from a bare base URL.
     */
    private fun explorer(
        blockstream: Boolean = false,
        expectSuccess: Boolean = true,
        answer: (path: String, times: Int) -> Pair<HttpStatusCode, String>,
    ): BlockchainExplorerApi {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val (status, body) = answer(path, requested.count { it == path })
            requested += path
            // Blockstream answers an unknown path with a plain-text 404.
            val type = if (status.isSuccess()) "application/json" else "text/plain"
            respond(body, status, headersOf(HttpHeaders.ContentType, type))
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            this.expectSuccess = expectSuccess
        }
        val baseUrl = "https://explorer.test/api"
        return if (blockstream) {
            BlockchainExplorerApi(SyncConfig.BlockStream(baseUrl = baseUrl), client)
        } else {
            BlockchainExplorerApi(baseUrl, client)
        }
    }

    private val esploraBackend: (String, Int) -> Pair<HttpStatusCode, String> = { path, _ ->
        if (path == feeEstimates) HttpStatusCode.OK to feeEstimatesJson else HttpStatusCode.NotFound to NOT_FOUND
    }
    private val mempoolBackend: (String, Int) -> Pair<HttpStatusCode, String> = { path, _ ->
        if (path == recommended) HttpStatusCode.OK to recommendedJson else HttpStatusCode.NotFound to NOT_FOUND
    }

    @Test
    fun aBaseUrlExplorerAsksRecommendedFees() = runTest {
        assertEquals(FeeEstimates(3, 2, 1, 1, 1), explorer(answer = mempoolBackend).getRecommendedFees())
        assertEquals(listOf(recommended), requested)
    }

    @Test
    fun aBlockstreamExplorerAsksFeeEstimatesWithoutAProbe() = runTest {
        assertEquals(mapped, explorer(blockstream = true, answer = esploraBackend).getRecommendedFees())
        assertEquals(listOf(feeEstimates), requested)
    }

    @Test
    fun aBaseUrlExplorerOnEsploraFallsBackAndRemembers() = runTest {
        val api = explorer(answer = esploraBackend)
        assertEquals(mapped, api.getRecommendedFees())
        assertEquals(mapped, api.getRecommendedFees())
        assertEquals(listOf(recommended, feeEstimates, feeEstimates), requested)
    }

    @Test
    fun aBlockstreamConfigOnAMempoolBackendFallsBackAndRemembers() = runTest {
        val api = explorer(blockstream = true, answer = mempoolBackend)
        assertEquals(FeeEstimates(3, 2, 1, 1, 1), api.getRecommendedFees())
        assertEquals(FeeEstimates(3, 2, 1, 1, 1), api.getRecommendedFees())
        assertEquals(listOf(feeEstimates, recommended, recommended), requested)
    }

    @Test
    fun aFailedFallbackIsNotRemembered() = runTest {
        // A one-off 404 on the endpoint that works, with no Esplora to fall back to.
        val api = explorer { path, times ->
            if (path == recommended && times > 0) HttpStatusCode.OK to recommendedJson else HttpStatusCode.NotFound to NOT_FOUND
        }
        assertFailsWith<IllegalStateException> { api.getRecommendedFees() }
        assertEquals(FeeEstimates(3, 2, 1, 1, 1), api.getRecommendedFees())
        assertEquals(listOf(recommended, feeEstimates, recommended), requested)
    }

    @Test
    fun anotherClientErrorIsNotTakenForTheWrongEndpoint() = runTest {
        val api = explorer { _, _ -> HttpStatusCode.Unauthorized to "unauthorized" }
        assertFailsWith<ClientRequestException> { api.getRecommendedFees() }
        assertEquals(listOf(recommended), requested)
    }

    @Test
    fun aClientWithoutExpectSuccessStillFallsBack() = runTest {
        val api = explorer(expectSuccess = false, answer = esploraBackend)
        assertEquals(mapped, api.getRecommendedFees())
        assertEquals(listOf(recommended, feeEstimates), requested)
    }
}
