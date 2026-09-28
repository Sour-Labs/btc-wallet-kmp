package io.sourlabs.btc.wallet.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RecommendedFeesEndpointTest {

    private val requested = mutableListOf<String>()

    /** An explorer that answers each path from [routes] with (status, JSON body). */
    private fun explorer(routes: Map<String, Pair<HttpStatusCode, String>>): BlockchainExplorerApi {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requested += path
            val (status, body) = routes[path] ?: (HttpStatusCode.NotFound to "")
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = true
        }
        return BlockchainExplorerApi("https://explorer.test/api", client)
    }

    @Test
    fun aMempoolStyleBackendAnswersRecommendedFees() = runTest {
        val api = explorer(
            mapOf(
                "/api/v1/fees/recommended" to (HttpStatusCode.OK to
                    """{"fastestFee":3,"halfHourFee":2,"hourFee":1,"economyFee":1,"minimumFee":1}"""),
            ),
        )
        assertEquals(FeeEstimates(3, 2, 1, 1, 1), api.getRecommendedFees())
        assertEquals(listOf("/api/v1/fees/recommended"), requested)
    }

    @Test
    fun anEsploraBackendFallsBackToFeeEstimatesAndIsRemembered() = runTest {
        val api = explorer(
            mapOf("/api/fee-estimates" to (HttpStatusCode.OK to """{"1":3.11,"3":2.11,"6":2.11,"144":0.28}""")),
        )
        val expected = FeeEstimates(fastestFee = 4, halfHourFee = 3, hourFee = 3, economyFee = 1, minimumFee = 1)
        assertEquals(expected, api.getRecommendedFees())
        assertEquals(expected, api.getRecommendedFees())
        assertEquals(listOf("/api/v1/fees/recommended", "/api/fee-estimates", "/api/fee-estimates"), requested)
    }

    @Test
    fun anotherClientErrorIsNotTakenForEsplora() = runTest {
        val api = explorer(mapOf("/api/v1/fees/recommended" to (HttpStatusCode.Unauthorized to "")))
        assertFailsWith<ClientRequestException> { api.getRecommendedFees() }
        assertEquals(listOf("/api/v1/fees/recommended"), requested)
    }
}
