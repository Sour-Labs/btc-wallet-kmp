package io.sourlabs.btc.wallet.utxo

import fr.acinq.bitcoin.ByteVector32
import io.sourlabs.btc.wallet.models.ScriptType
import io.sourlabs.btc.wallet.models.UnspentOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnspentOutputSelectorTest {

    private val selector = UnspentOutputSelector(dustThreshold = 546)

    private fun createUtxo(value: Long, confirmations: Int = 6): UnspentOutput {
        return UnspentOutput(
            transactionHash = ByteVector32.Zeroes,
            outputIndex = 0,
            value = value,
            scriptPubKey = ByteArray(22),
            scriptType = ScriptType.P2WPKH,
            confirmations = confirmations,
            publicKeyPath = "m/84'/0'/0'/0/0",
            blockHeight = 100,
            isSpendable = true
        )
    }

    @Test
    fun testBasicSelection() {
        val utxos = listOf(
            createUtxo(100_000),
            createUtxo(50_000),
            createUtxo(25_000)
        )

        val result = selector.select(utxos, 50_000, 10)
        assertNotNull(result)
        assertTrue(result.totalInput >= result.sendAmount + result.fee)
    }

    @Test
    fun testInsufficientFunds() {
        val utxos = listOf(
            createUtxo(10_000)
        )

        val result = selector.select(utxos, 100_000, 10)
        assertNull(result, "Should return null when insufficient funds")
    }

    @Test
    fun testLargestFirstStrategy() {
        val utxos = listOf(
            createUtxo(10_000),
            createUtxo(100_000),
            createUtxo(50_000)
        )

        val result = selector.select(utxos, 80_000, 10, SelectionStrategy.LARGEST_FIRST)
        assertNotNull(result)
        // Should select the 100k UTXO first
        assertEquals(1, result.selectedUtxos.size)
        assertEquals(100_000, result.selectedUtxos[0].value)
    }

    @Test
    fun testSmallestFirstStrategy() {
        val utxos = listOf(
            createUtxo(10_000),
            createUtxo(100_000),
            createUtxo(50_000)
        )

        val result = selector.select(utxos, 5_000, 10, SelectionStrategy.SMALLEST_FIRST)
        assertNotNull(result)
        // Should select the 10k UTXO first
        assertEquals(10_000, result.selectedUtxos[0].value)
    }

    @Test
    fun testChangeCalculation() {
        val utxos = listOf(
            createUtxo(100_000)
        )

        val result = selector.select(utxos, 50_000, 1)
        assertNotNull(result)
        assertTrue(result.hasChange)
        assertTrue(result.change > 0)
        assertEquals(result.totalInput, result.sendAmount + result.fee + result.change)
    }

    @Test
    fun testNoChangeBelowDust() {
        // Use a UTXO with just enough funds that change would be dust
        // Input: 100,000 sats, sending 99,200 sats at 5 sat/vB
        // Estimated fee for 1 input, 1 output ~550 sats
        // Change would be ~250 sats which is below dust (546)
        val utxos = listOf(
            createUtxo(100_000)
        )

        val result = selector.select(utxos, 99_200, 5)
        assertNotNull(result)
        // Change should be absorbed into fee if below dust
        assertEquals(0L, result.change, "Change should be absorbed into fee when below dust")
        // Fee should be higher than normal since it absorbs the dust
        assertTrue(result.fee > 0, "Fee should be positive")
        // Invariant check
        assertEquals(result.totalInput, result.sendAmount + result.fee + result.change)
    }

    @Test
    fun testManualSelection() {
        val utxos = listOf(
            createUtxo(50_000),
            createUtxo(30_000)
        )

        val result = selector.selectManual(utxos, 60_000, 10)
        assertNotNull(result)
        assertEquals(2, result.selectedUtxos.size)
        assertEquals(80_000, result.totalInput)
    }

    @Test
    fun testEmptyUtxoList() {
        val result = selector.select(emptyList(), 10_000, 10)
        assertNull(result)
    }
}
