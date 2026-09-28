package io.sourlabs.btc.wallet.utxo

import fr.acinq.bitcoin.ByteVector32
import io.sourlabs.btc.wallet.models.ScriptType
import io.sourlabs.btc.wallet.models.UnspentOutput
import io.sourlabs.btc.wallet.transactions.FeeCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnspentOutputSelectorTest {

    // P2WPKH wallet — matches the test UTXO scriptType below for "all defaults" tests.
    private val selector = UnspentOutputSelector(walletScriptType = ScriptType.P2WPKH)

    // Default destination: another P2WPKH (bc1q…) address — keeps the fee numbers
    // matching the legacy "everything is P2WPKH" assumption tests were written against.
    private val p2wpkhDestination = ScriptType.P2WPKH

    private fun createUtxo(
        value: Long,
        blockHeight: Int? = 100,
        scriptType: ScriptType = ScriptType.P2WPKH,
    ): UnspentOutput {
        return UnspentOutput(
            transactionHash = ByteVector32.Zeroes,
            outputIndex = 0,
            value = value,
            scriptPubKey = ByteArray(22),
            scriptType = scriptType,
            publicKeyPath = "m/84'/0'/0'/0/0",
            blockHeight = blockHeight,
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

        val result = selector.select(utxos, 50_000, 10, p2wpkhDestination)
        assertNotNull(result)
        assertTrue(result.totalInput >= result.sendAmount + result.fee)
    }

    @Test
    fun testInsufficientFunds() {
        val utxos = listOf(
            createUtxo(10_000)
        )

        val result = selector.select(utxos, 100_000, 10, p2wpkhDestination)
        assertNull(result, "Should return null when insufficient funds")
    }

    @Test
    fun testLargestFirstStrategy() {
        val utxos = listOf(
            createUtxo(10_000),
            createUtxo(100_000),
            createUtxo(50_000)
        )

        val result = selector.select(utxos, 80_000, 10, p2wpkhDestination, SelectionStrategy.LARGEST_FIRST)
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

        val result = selector.select(utxos, 5_000, 10, p2wpkhDestination, SelectionStrategy.SMALLEST_FIRST)
        assertNotNull(result)
        // Should select the 10k UTXO first
        assertEquals(10_000, result.selectedUtxos[0].value)
    }

    @Test
    fun testChangeCalculation() {
        val utxos = listOf(
            createUtxo(100_000)
        )

        val result = selector.select(utxos, 50_000, 1, p2wpkhDestination)
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
        // Change would be ~90 sats which is below P2WPKH dust (294)
        val utxos = listOf(
            createUtxo(100_000)
        )

        val result = selector.select(utxos, 99_200, 5, p2wpkhDestination)
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

        val result = selector.selectManual(utxos, 60_000, 10, p2wpkhDestination)
        assertNotNull(result)
        assertEquals(2, result.selectedUtxos.size)
        assertEquals(80_000, result.totalInput)
    }

    @Test
    fun testEmptyUtxoList() {
        val result = selector.select(emptyList(), 10_000, 10, p2wpkhDestination)
        assertNull(result)
    }

    // ─── Subtract-fee selection semantics (crypto review F2) ───

    /**
     * Subtract-fee with change: coverage target is the amount alone, the
     * destination gets amount − feeWithChange, and change is totalInput − amount.
     */
    @Test
    fun subtractFeeSelectionWithChange() {
        val utxos = listOf(createUtxo(100_000))
        val feeRate = 10L

        val result = selector.select(
            utxos, 50_000, feeRate, p2wpkhDestination,
            subtractFeeFromAmount = true,
        )

        assertNotNull(result)
        val expectedFee = FeeCalculator.estimateFee(
            listOf(ScriptType.P2WPKH), listOf(ScriptType.P2WPKH, ScriptType.P2WPKH), feeRate,
        )
        assertEquals(expectedFee, result.fee)
        assertEquals(50_000 - expectedFee, result.sendAmount, "destination gets amount − fee")
        assertEquals(50_000, result.change, "change is totalInput − amount, independent of fee")
        assertEquals(result.totalInput, result.sendAmount + result.fee + result.change)
    }

    /**
     * Subtract-fee exact balance: totalInput == targetAmount must be selectable
     * (this used to return null because selection demanded amount + fee).
     */
    @Test
    fun subtractFeeSelectionAllowsExactBalance() {
        val utxos = listOf(createUtxo(100_000))
        val feeRate = 10L

        val result = selector.select(
            utxos, 100_000, feeRate, p2wpkhDestination,
            subtractFeeFromAmount = true,
        )

        assertNotNull(result, "exact-balance subtract-fee send must be selectable")
        val expectedFee = FeeCalculator.estimateFee(
            listOf(ScriptType.P2WPKH), listOf(ScriptType.P2WPKH), feeRate,
        )
        assertEquals(expectedFee, result.fee)
        assertEquals(100_000 - expectedFee, result.sendAmount)
        assertEquals(0L, result.change)
        assertEquals(result.totalInput, result.sendAmount + result.fee + result.change)
    }

    /**
     * Subtract-fee with a sub-dust residual: the residual is absorbed into the
     * fee exactly once — sendAmount is still amount − feeWithoutChange.
     */
    @Test
    fun subtractFeeSelectionAbsorbsSubDustResidualIntoFeeOnce() {
        val utxos = listOf(createUtxo(100_000))
        val feeRate = 10L

        // Residual 100_000 − 99_710 = 290 < P2WPKH dust (294) → no change output.
        val result = selector.select(
            utxos, 99_710, feeRate, p2wpkhDestination,
            subtractFeeFromAmount = true,
        )

        assertNotNull(result)
        val feeWithoutChange = FeeCalculator.estimateFee(
            listOf(ScriptType.P2WPKH), listOf(ScriptType.P2WPKH), feeRate,
        )
        assertEquals(0L, result.change)
        assertEquals(feeWithoutChange + 290, result.fee, "fee absorbs the residual")
        assertEquals(99_710 - feeWithoutChange, result.sendAmount, "residual must not also come out of the destination")
        assertEquals(result.totalInput, result.sendAmount + result.fee + result.change)
    }

    @Test
    fun subtractFeeManualSelection() {
        val utxos = listOf(createUtxo(50_000), createUtxo(30_000))
        val feeRate = 10L

        // Exact balance across both UTXOs.
        val result = selector.selectManual(
            utxos, 80_000, feeRate, p2wpkhDestination,
            subtractFeeFromAmount = true,
        )

        assertNotNull(result, "manual exact-balance subtract-fee send must be selectable")
        val expectedFee = FeeCalculator.estimateFee(
            listOf(ScriptType.P2WPKH, ScriptType.P2WPKH), listOf(ScriptType.P2WPKH), feeRate,
        )
        assertEquals(expectedFee, result.fee)
        assertEquals(80_000 - expectedFee, result.sendAmount)
        assertEquals(0L, result.change)
        assertEquals(result.totalInput, result.sendAmount + result.fee + result.change)
    }

    // ─── PR-07 regression anchors: per-script-type fee sizing ───

    /**
     * Anchors the H6 fix: BIP-44 inputs are ~148 vbytes each — over 2× a P2WPKH
     * input. The pre-PR-07 selector hardcoded `inputCount * 68` and would have
     * massively under-estimated the fee for a 5-input BIP-44 selection.
     *
     * Verifies the fee for a 5-input BIP-44 spend is roughly proportional to
     * `5 * 148 * feeRate` rather than `5 * 68 * feeRate`.
     */
    @Test
    fun bip44SelectionUsesP2pkhInputSize() {
        // P2PKH-typed wallet + P2PKH UTXOs.
        val p2pkhSelector = UnspentOutputSelector(walletScriptType = ScriptType.P2PKH)
        val utxos = (0 until 5).map { createUtxo(20_000, scriptType = ScriptType.P2PKH) }
        val feeRate = 10L
        // Spend close to all of it so all 5 inputs are needed.
        val result = p2pkhSelector.select(
            utxos = utxos,
            targetAmount = 90_000,
            feeRate = feeRate,
            destinationScriptType = ScriptType.P2PKH,
        )
        assertNotNull(result)
        assertEquals(5, result.selectedUtxos.size, "needs all 5 inputs at this size")

        // 5 * 148 (P2PKH inputs) + 2 * 34 (P2PKH outputs: destination + change) + 10 (header)
        // = 740 + 68 + 10 = 818 vbytes. At feeRate=10 that's ~8180 sats.
        // The old hardcoded `5 * 68 = 340`-based fee would have been ~3500 sats — wrong by ~2.3×.
        val expectedFeeFloor = 5 * 148 * feeRate  // 7400 sats — lower bound, ignoring outputs+header
        val expectedFeeCeiling = (5 * 148 + 2 * 34 + 10 + 8) * feeRate  // generous upper
        assertTrue(
            result.fee in expectedFeeFloor..expectedFeeCeiling,
            "P2PKH fee should reflect 148 vB/input, got ${result.fee} sats (expected in $expectedFeeFloor..$expectedFeeCeiling)",
        )
    }

    /**
     * Anchors the H6 fix for P2WPKH: the previous code happened to match this
     * scenario (both used 68 vB/input), so this verifies the new code didn't
     * regress the previously-correct case.
     */
    @Test
    fun bip84SelectionFeeStillReasonable() {
        val utxos = (0 until 3).map { createUtxo(50_000, scriptType = ScriptType.P2WPKH) }
        val feeRate = 10L
        val result = selector.select(utxos, 80_000, feeRate, p2wpkhDestination)
        assertNotNull(result)
        // 2 inputs needed (50k+50k > 80k+fee), output 31 + change 31 + header 10 + witness overhead.
        // Per-input vSize ≈ 68. So fee ≈ (2*68 + 31 + 31 + 10) * 10 ≈ 2080.
        assertTrue(result.fee in 1500..2500, "P2WPKH fee out of expected band: ${result.fee}")
    }

    // ─── Change dust limit follows the wallet's script type ───

    /**
     * Spends one coin of the wallet's own type with a target chosen so the
     * change comes out at exactly [change] sats.
     */
    private fun selectWithChange(
        walletScriptType: ScriptType,
        change: Long,
        subtractFeeFromAmount: Boolean = false,
    ): SelectionResult {
        val totalInput = 100_000L
        val feeRate = 1L
        val feeWithChange = FeeCalculator.estimateFee(
            listOf(walletScriptType), listOf(p2wpkhDestination, walletScriptType), feeRate,
        )
        val target = if (subtractFeeFromAmount) totalInput - change else totalInput - feeWithChange - change
        val result = UnspentOutputSelector(walletScriptType).select(
            listOf(createUtxo(totalInput, scriptType = walletScriptType)), target, feeRate, p2wpkhDestination,
            subtractFeeFromAmount = subtractFeeFromAmount,
        )
        assertNotNull(result)
        assertEquals(result.totalInput, result.sendAmount + result.fee + result.change)
        return result
    }

    @Test
    fun changeDustLimitFollowsWalletScriptType() {
        val limits = mapOf(
            ScriptType.P2WPKH to 294L,
            ScriptType.P2TR to 330L,
            ScriptType.P2SH_P2WPKH to 540L,
            ScriptType.P2PKH to 546L,
        )
        for ((type, limit) in limits) {
            assertEquals(limit, selectWithChange(type, limit).change, "$type must keep change at its dust limit")
            assertEquals(0L, selectWithChange(type, limit - 1).change, "$type must drop change below its dust limit")
        }
    }

    @Test
    fun subtractFeeChangeDustLimitFollowsWalletScriptType() {
        assertEquals(294L, selectWithChange(ScriptType.P2WPKH, 294, subtractFeeFromAmount = true).change)
        assertEquals(0L, selectWithChange(ScriptType.P2WPKH, 293, subtractFeeFromAmount = true).change)
    }
}
