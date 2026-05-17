package io.sourlabs.btc.wallet.utxo

import io.sourlabs.btc.wallet.models.ScriptType
import io.sourlabs.btc.wallet.models.UnspentOutput
import io.sourlabs.btc.wallet.transactions.FeeCalculator

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
 *
 * Sizes inputs by their actual [ScriptType] (via [FeeCalculator]) rather than
 * assuming P2WPKH — so BIP-44 wallets no longer under-estimate fees by ~50%,
 * and BIP-86 fees aren't over-stated. The wallet's own script type also drives
 * change-output sizing.
 *
 * @param walletScriptType the wallet's own script type — used to size both the
 *   wallet's *change* output and the wallet's own inputs that lack a per-UTXO
 *   scriptType tag at selection time (each selected UTXO actually carries its
 *   own scriptType; this is the fallback when a hypothetical input is being
 *   sized without a chosen UTXO).
 * @param dustThreshold change outputs smaller than this are absorbed into the
 *   fee instead of being emitted (standard Bitcoin Core default: 546 sats).
 */
class UnspentOutputSelector(
    private val walletScriptType: ScriptType,
    private val dustThreshold: Long = 546,
) {
    /**
     * Select UTXOs to cover the target amount plus fees.
     *
     * @param utxos available UTXOs to select from
     * @param targetAmount amount to send in satoshis
     * @param feeRate fee rate in sat/vB
     * @param destinationScriptType the script type of the *destination* output,
     *   so fee estimation uses the correct output size
     * @param strategy selection strategy
     */
    fun select(
        utxos: List<UnspentOutput>,
        targetAmount: Long,
        feeRate: Long,
        destinationScriptType: ScriptType,
        strategy: SelectionStrategy = SelectionStrategy.AUTOMATIC,
    ): SelectionResult? {
        if (utxos.isEmpty()) return null

        val sortedUtxos = when (strategy) {
            SelectionStrategy.AUTOMATIC -> utxos.sortedByDescending { it.value }
            // Lower blockHeight = older. Unconfirmed UTXOs (no blockHeight) sort last.
            SelectionStrategy.OLDEST_FIRST -> utxos.sortedBy { it.blockHeight ?: Int.MAX_VALUE }
            SelectionStrategy.LARGEST_FIRST -> utxos.sortedByDescending { it.value }
            SelectionStrategy.SMALLEST_FIRST -> utxos.sortedBy { it.value }
            SelectionStrategy.PRIVACY_OPTIMIZED -> selectForPrivacy(utxos, targetAmount, feeRate, destinationScriptType)
        }

        return selectFromSorted(sortedUtxos, targetAmount, feeRate, destinationScriptType)
    }

    /**
     * Select specific UTXOs (manual selection).
     */
    fun selectManual(
        utxos: List<UnspentOutput>,
        targetAmount: Long,
        feeRate: Long,
        destinationScriptType: ScriptType,
    ): SelectionResult? {
        val totalInput = utxos.sumOf { it.value }

        val feeWithoutChange = estimateFeeForInputsAndOutputs(
            inputs = utxos.map { it.scriptType },
            outputs = listOf(destinationScriptType),
            feeRate = feeRate,
        )
        val feeWithChange = estimateFeeForInputsAndOutputs(
            inputs = utxos.map { it.scriptType },
            outputs = listOf(destinationScriptType, walletScriptType),
            feeRate = feeRate,
        )

        if (totalInput < targetAmount + feeWithoutChange) {
            return null // Insufficient funds
        }

        val potentialChange = totalInput - targetAmount - feeWithChange
        val (fee, change) = if (potentialChange > dustThreshold) {
            feeWithChange to potentialChange
        } else {
            // Change would be dust — drop the change output and let the fee absorb it.
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
        destinationScriptType: ScriptType,
    ): SelectionResult? {
        val selected = mutableListOf<UnspentOutput>()
        var totalInput = 0L

        for (utxo in sortedUtxos) {
            selected.add(utxo)
            totalInput += utxo.value
            val inputScriptTypes = selected.map { it.scriptType }

            val feeWithChange = estimateFeeForInputsAndOutputs(
                inputs = inputScriptTypes,
                outputs = listOf(destinationScriptType, walletScriptType),
                feeRate = feeRate,
            )
            val feeWithoutChange = estimateFeeForInputsAndOutputs(
                inputs = inputScriptTypes,
                outputs = listOf(destinationScriptType),
                feeRate = feeRate,
            )

            if (totalInput >= targetAmount + feeWithoutChange) {
                val potentialChange = totalInput - targetAmount - feeWithChange
                val (fee, change) = if (potentialChange > dustThreshold) {
                    feeWithChange to potentialChange
                } else {
                    // Sub-dust change — drop the change output, fee absorbs the residual.
                    (totalInput - targetAmount) to 0L
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
        feeRate: Long,
        destinationScriptType: ScriptType,
    ): List<UnspentOutput> {
        // Simple privacy optimization: try to find a single UTXO that covers the amount
        // to avoid linking multiple UTXOs together. Use the actual fee estimate (sized
        // against the candidate UTXO's own script type) so we don't prematurely stop
        // on a UTXO that cannot fund the transaction.
        val singleUtxo = utxos.find { utxo ->
            val fee = estimateFeeForInputsAndOutputs(
                inputs = listOf(utxo.scriptType),
                outputs = listOf(destinationScriptType),
                feeRate = feeRate,
            )
            utxo.value >= targetAmount + fee
        }
        if (singleUtxo != null) {
            return listOf(singleUtxo)
        }

        // Fall back to largest first
        return utxos.sortedByDescending { it.value }
    }

    private fun estimateFeeForInputsAndOutputs(
        inputs: List<ScriptType>,
        outputs: List<ScriptType>,
        feeRate: Long,
    ): Long = FeeCalculator.estimateFee(inputs, outputs, feeRate)
}
