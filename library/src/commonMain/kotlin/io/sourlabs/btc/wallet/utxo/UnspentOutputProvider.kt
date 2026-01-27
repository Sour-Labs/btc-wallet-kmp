package io.sourlabs.btc.wallet.utxo

import io.sourlabs.btc.wallet.models.BalanceInfo
import io.sourlabs.btc.wallet.models.UnspentOutput
import io.sourlabs.btc.wallet.storage.UnspentOutputStorage

/**
 * Provides access to unspent outputs and calculates balance.
 */
class UnspentOutputProvider(
    private val storage: UnspentOutputStorage,
    private val confirmationsThreshold: Int = 1
) {
    /**
     * Get all UTXOs.
     */
    suspend fun getAllUtxos(): List<UnspentOutput> {
        return storage.getAllUtxos()
    }

    /**
     * Get spendable UTXOs (confirmed and not locked).
     */
    suspend fun getSpendableUtxos(): List<UnspentOutput> {
        return storage.getAllUtxos().filter { isSpendable(it) }
    }

    /**
     * Get UTXOs with at least the specified number of confirmations.
     */
    suspend fun getConfirmedUtxos(minConfirmations: Int = confirmationsThreshold): List<UnspentOutput> {
        return storage.getAllUtxos().filter { it.confirmations >= minConfirmations }
    }

    /**
     * Calculate the total balance broken down by spendability.
     */
    suspend fun getBalance(): BalanceInfo {
        val utxos = storage.getAllUtxos()

        var spendable = 0L
        var unconfirmed = 0L
        var locked = 0L

        for (utxo in utxos) {
            when {
                !utxo.isSpendable -> locked += utxo.value
                utxo.confirmations < confirmationsThreshold -> unconfirmed += utxo.value
                else -> spendable += utxo.value
            }
        }

        return BalanceInfo(
            spendable = spendable,
            unconfirmed = unconfirmed,
            locked = locked
        )
    }

    /**
     * Calculate the maximum spendable amount after accounting for fees.
     * @param feeRate fee rate in sat/vB
     * @param outputScriptSize estimated size of the output script (default 34 for P2WPKH)
     */
    suspend fun getMaximumSpendable(feeRate: Long, outputScriptSize: Int = 34): Long {
        val spendableUtxos = getSpendableUtxos()
        if (spendableUtxos.isEmpty()) return 0

        val totalValue = spendableUtxos.sumOf { it.value }

        // Estimate transaction size
        // Base size: version (4) + marker (1) + flag (1) + input count (1) + output count (1) + locktime (4) = 12
        // Each input: ~68 vBytes for P2WPKH
        // Single output: 8 (value) + 1 (script length) + script
        val baseTxSize = 12
        val inputSize = spendableUtxos.size * 68 // Approximate for segwit
        val outputSize = 8 + 1 + outputScriptSize

        val estimatedSize = baseTxSize + inputSize + outputSize
        val estimatedFee = estimatedSize * feeRate

        return maxOf(0, totalValue - estimatedFee)
    }

    /**
     * Get the total value of all spendable UTXOs.
     */
    suspend fun getSpendableValue(): Long {
        return getSpendableUtxos().sumOf { it.value }
    }

    /**
     * Check if a UTXO is spendable based on confirmations and lock status.
     */
    private fun isSpendable(utxo: UnspentOutput): Boolean {
        return utxo.isSpendable && utxo.confirmations >= confirmationsThreshold
    }

    /**
     * Check if the wallet has sufficient funds.
     */
    suspend fun hasSufficientFunds(amount: Long, feeRate: Long): Boolean {
        return getMaximumSpendable(feeRate) >= amount
    }
}
