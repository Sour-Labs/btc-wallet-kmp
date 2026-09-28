package io.sourlabs.btc.wallet.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EsploraFeeEstimatesTest {

    @Test
    fun mapsConfirmationTargetsToTiers() {
        // Blockstream mainnet, 2026-09-28 (trimmed).
        val estimates = mapOf(
            "1" to 3.11, "2" to 3.11, "3" to 2.11, "4" to 2.11, "5" to 2.11, "6" to 2.11,
            "7" to 1.22, "12" to 1.0, "25" to 0.3, "144" to 0.28, "504" to 0.25, "1008" to 0.1,
        )
        assertEquals(
            FeeEstimates(fastestFee = 4, halfHourFee = 3, hourFee = 3, economyFee = 1, minimumFee = 1),
            esploraFeeEstimates(estimates),
        )
    }

    @Test
    fun roundsUpAndNeverGoesBelowTheMinimumRelayFee() {
        val estimates = mapOf("1" to 1.2, "3" to 0.5, "6" to 0.3, "144" to 0.0)
        assertEquals(
            FeeEstimates(fastestFee = 2, halfHourFee = 1, hourFee = 1, economyFee = 1, minimumFee = 1),
            esploraFeeEstimates(estimates),
        )
    }

    @Test
    fun floatingPointNoiseAddsNoSatPerVbyte() {
        // Whole sat/kvB quotes after Esplora's `btc_per_kvb * 100_000f64`.
        val estimates = mapOf("1" to 7.000000000000001, "3" to 5.000000000000001, "6" to 2.9999999999999996, "144" to 1.0)
        assertEquals(
            FeeEstimates(fastestFee = 7, halfHourFee = 5, hourFee = 3, economyFee = 1, minimumFee = 1),
            esploraFeeEstimates(estimates),
        )
    }

    @Test
    fun aFasterTierIsNeverCheaper() {
        // Every slower target quotes more than the faster one, so each faster tier is raised,
        // as mempool.space's fee-api does ("ensure recommendations always increase w/ priority").
        val estimates = mapOf("1" to 5.0, "3" to 6.0, "6" to 7.0, "144" to 8.0)
        assertEquals(
            FeeEstimates(fastestFee = 8, halfHourFee = 8, hourFee = 8, economyFee = 8, minimumFee = 1),
            esploraFeeEstimates(estimates),
        )
    }

    @Test
    fun anUnlistedTargetTakesTheNextFasterListedOne() {
        // 1 has nothing at or below it, so it takes the fastest listed (2); 3 takes 2;
        // 6 takes 5; 144 takes 25.
        val estimates = mapOf("2" to 8.0, "5" to 4.0, "25" to 2.0)
        assertEquals(
            FeeEstimates(fastestFee = 8, halfHourFee = 8, hourFee = 4, economyFee = 2, minimumFee = 1),
            esploraFeeEstimates(estimates),
        )
    }

    @Test
    fun refusesAnEmptyResponse() {
        assertFailsWith<IllegalArgumentException> { esploraFeeEstimates(emptyMap()) }
    }
}
