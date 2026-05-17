package io.sourlabs.btc.wallet.sync

import io.sourlabs.btc.wallet.api.ScanException
import io.sourlabs.btc.wallet.keys.SeedManager.toSeed
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Anchors PR-05 (audit findings H4 and H3-by-deletion).
 *
 * H4: persistent API failures during scanPurpose must abort with [ScanException]
 * rather than silently report "no activity." Restoration callers can't tell
 * "wallet has no funds" from "the explorer was down" if the scanner swallows
 * exceptions.
 *
 * H3 (deletion side): the broken `scan(): Flow<ScanProgress>` is gone. No
 * external callers existed; `scanAll()` is the supported entry point.
 */
class MultiPurposeScannerTest {

    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    /** Stub that always throws on getAddress. */
    private class AlwaysFailingApi : BlockchainExplorerApi(baseUrl = "http://test.invalid/") {
        var calls = 0
            private set

        override suspend fun getAddress(address: String): AddressResponse {
            calls++
            throw RuntimeException("simulated network failure")
        }
    }

    /** Stub that returns "empty" (chain_stats.tx_count = 0) for every address. */
    private class AlwaysEmptyApi : BlockchainExplorerApi(baseUrl = "http://test.invalid/") {
        var calls = 0
            private set

        override suspend fun getAddress(address: String): AddressResponse {
            calls++
            return AddressResponse(
                address = address,
                chainStats = AddressStats(0, 0, 0, 0, txCount = 0),
                mempoolStats = AddressStats(0, 0, 0, 0, txCount = 0),
            )
        }

        override suspend fun getAddressUtxos(address: String): List<ApiUtxo> = emptyList()
    }

    @Test
    fun scanPurposeAbortsAfterMaxConsecutiveFailures() = runTest {
        val seed = testMnemonic.toSeed()
        val api = AlwaysFailingApi()
        val scanner = MultiPurposeScanner(
            seed = seed,
            network = Network.MAINNET,
            api = api,
            gapLimit = 20,
            maxConsecutiveFailures = 3,
        )
        val ex = assertFailsWith<ScanException> {
            scanner.scanPurpose(Purpose.BIP84)
        }
        // The third consecutive failure should be the one that throws — earlier
        // ones log + continue. Scanner walks external chain first, so it bails
        // before touching internal.
        assertEquals(3, api.calls, "scanner should abort after exactly maxConsecutiveFailures probes")
        assertTrue(ex.message!!.contains("consecutive API failures"))
    }

    @Test
    fun scanAllAbortsOnPersistentFailure() = runTest {
        // scanAll calls scanPurpose for each purpose; the first persistent
        // failure should propagate out of scanAll too, not be swallowed.
        val seed = testMnemonic.toSeed()
        val scanner = MultiPurposeScanner(
            seed = seed,
            network = Network.MAINNET,
            api = AlwaysFailingApi(),
            gapLimit = 20,
            maxConsecutiveFailures = 3,
        )
        assertFailsWith<ScanException> { scanner.scanAll() }
    }

    @Test
    fun scanPurposeReturnsZeroBalanceForEmptyChain() = runTest {
        val seed = testMnemonic.toSeed()
        val api = AlwaysEmptyApi()
        val scanner = MultiPurposeScanner(
            seed = seed,
            network = Network.MAINNET,
            api = api,
            gapLimit = 5,  // small so the test runs fast
        )
        val result = scanner.scanPurpose(Purpose.BIP84)
        assertEquals(0, result.externalAddressesUsed)
        assertEquals(0, result.internalAddressesUsed)
        assertEquals(0L, result.balance)
        assertEquals(0, result.transactionCount)
        // 5 external probes + 5 internal = 10 getAddress calls (each empty).
        assertEquals(10, api.calls)
    }
}
