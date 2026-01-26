package io.sourlabs.btc.wallet.utxo

import io.sourlabs.btc.wallet.models.UnspentOutput

/**
 * Strategy for selecting UTXOs for a transaction.
 */
enum class SelectionStrategy {
    /**
     * Optimize for minimum fees and reasonable privacy.
     */
    AUTOMATIC,

    /**
     * Select oldest UTXOs first (FIFO).
     */
    OLDEST_FIRST,

    /**
     * Select largest UTXOs first to minimize number of inputs.
     */
    LARGEST_FIRST,

    /**
     * Select smallest UTXOs first for consolidation.
     */
    SMALLEST_FIRST,

    /**
     * Optimize for privacy by avoiding linking UTXOs.
     */
    PRIVACY_OPTIMIZED
}

/**
 * Result of UTXO selection.
 */
data class SelectionResult(
    /**
     * Selected UTXOs to use as inputs.
     */
    val selectedUtxos: List<UnspentOutput>,

    /**
     * Total value of selected inputs.
     */
    val totalInput: Long,

    /**
     * Amount being sent (excluding change).
     */
    val sendAmount: Long,

    /**
     * Estimated fee.
     */
    val fee: Long,

    /**
     * Change amount (totalInput - sendAmount - fee).
     */
    val change: Long
) {
    val hasChange: Boolean
        get() = change > 0
}

/**
 * Selects UTXOs for spending based on the chosen strategy.
 */
class UnspentOutputSelector(
    private val dustThreshold: Long = 546 // Standard dust threshold for P2PKH
) {
    /**
     * Select UTXOs to cover the target amount plus fees.
     *
     * @param utxos available UTXOs to select from
     * @param targetAmount amount to send in satoshis
     * @param feeRate fee rate in sat/vB
     * @param strategy selection strategy
     * @param changeOutputSize estimated size of change output script (default 34 for P2WPKH)
     * @return selection result or null if insufficient funds
     */
    fun select(
        utxos: List<UnspentOutput>,
        targetAmount: Long,
        feeRate: Long,
        strategy: SelectionStrategy = SelectionStrategy.AUTOMATIC,
        changeOutputSize: Int = 34
    ): SelectionResult? {
        if (utxos.isEmpty()) return null

        val sortedUtxos = when (strategy) {
            SelectionStrategy.AUTOMATIC -> utxos.sortedByDescending { it.value }
            SelectionStrategy.OLDEST_FIRST -> utxos.sortedBy { it.confirmations }.reversed()
            SelectionStrategy.LARGEST_FIRST -> utxos.sortedByDescending { it.value }
            SelectionStrategy.SMALLEST_FIRST -> utxos.sortedBy { it.value }
            SelectionStrategy.PRIVACY_OPTIMIZED -> selectForPrivacy(utxos, targetAmount, feeRate)
        }

        return selectFromSorted(sortedUtxos, targetAmount, feeRate, changeOutputSize)
    }

    /**
     * Select specific UTXOs (manual selection).
     */
    fun selectManual(
        utxos: List<UnspentOutput>,
        targetAmount: Long,
        feeRate: Long,
        changeOutputSize: Int = 34
    ): SelectionResult? {
        val totalInput = utxos.sumOf { it.value }

        // Calculate fee with and without change output
        val feeWithoutChange = estimateFee(utxos.size, 1, feeRate)
        val feeWithChange = estimateFee(utxos.size, 2, feeRate, changeOutputSize)

        // Check if we have enough without change
        if (totalInput < targetAmount + feeWithoutChange) {
            return null // Insufficient funds
        }

        // Determine if we need change
        val potentialChange = totalInput - targetAmount - feeWithChange
        val (fee, change) = if (potentialChange > dustThreshold) {
            feeWithChange to potentialChange
        } else {
            // No change output, fee absorbs the dust
            (totalInput - targetAmount) to 0L
        }

        return SelectionResult(
            selectedUtxos = utxos,
            totalInput = totalInput,
            sendAmount = targetAmount,
            fee = fee,
            change = change
        )
    }

    private fun selectFromSorted(
        sortedUtxos: List<UnspentOutput>,
        targetAmount: Long,
        feeRate: Long,
        changeOutputSize: Int
    ): SelectionResult? {
        val selected = mutableListOf<UnspentOutput>()
        var totalInput = 0L

        for (utxo in sortedUtxos) {
            selected.add(utxo)
            totalInput += utxo.value

            // Calculate fee with current selection
            val feeWithChange = estimateFee(selected.size, 2, feeRate, changeOutputSize)
            val feeWithoutChange = estimateFee(selected.size, 1, feeRate)

            // Check if we have enough
            if (totalInput >= targetAmount + feeWithoutChange) {
                val potentialChange = totalInput - targetAmount - feeWithChange

                val (fee, change) = if (potentialChange > dustThreshold) {
                    feeWithChange to potentialChange
                } else if (totalInput >= targetAmount + feeWithoutChange) {
                    (totalInput - targetAmount) to 0L
                } else {
                    continue // Need more inputs
                }

                return SelectionResult(
                    selectedUtxos = selected.toList(),
                    totalInput = totalInput,
                    sendAmount = targetAmount,
                    fee = fee,
                    change = change
                )
            }
        }

        return null // Insufficient funds
    }

    private fun selectForPrivacy(
        utxos: List<UnspentOutput>,
        targetAmount: Long,
        feeRate: Long
    ): List<UnspentOutput> {
        // Simple privacy optimization: try to find a single UTXO that covers the amount
        // to avoid linking multiple UTXOs together. Use the actual fee estimate so we
        // don't prematurely stop on a UTXO that cannot fund the transaction.
        val minimumRequired = targetAmount + estimateFee(1, 1, feeRate)
        val singleUtxo = utxos.find { it.value >= minimumRequired }
        if (singleUtxo != null) {
            return listOf(singleUtxo)
        }

        // Fall back to largest first
        return utxos.sortedByDescending { it.value }
    }

    /**
     * Estimate transaction fee.
     *
     * @param inputCount number of inputs
     * @param outputCount number of outputs
     * @param feeRate fee rate in sat/vB
     * @param changeOutputSize size of change output script (if applicable)
     */
    private fun estimateFee(
        inputCount: Int,
        outputCount: Int,
        feeRate: Long,
        changeOutputSize: Int = 34
    ): Long {
        // Estimate virtual size for native SegWit transaction
        // Header: 10.5 vBytes (4 version + 0.25 marker + 0.25 flag + 1 input count + 1 output count + 4 locktime)
        // Input: ~68 vBytes each (41 non-witness + 27 witness / 4)
        // Output: 8 + 1 + scriptSize vBytes each

        val headerSize = 11 // Rounded
        val inputSize = inputCount * 68
        val outputSize = outputCount * (9 + changeOutputSize)

        val vSize = headerSize + inputSize + outputSize
        return vSize * feeRate
    }

    /**
     * Get dust threshold for a given script type size.
     */
    fun getDustThreshold(scriptSize: Int = 34): Long {
        // Standard dust calculation: 3 * (input vsize) * minRelayFee
        // For P2WPKH input: 68 vBytes, minRelayFee: 1 sat/vB
        // Dust = 3 * 68 * 1 = 204 sats (but commonly 546 is used)
        return dustThreshold
    }
}
