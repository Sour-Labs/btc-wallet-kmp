package io.sourlabs.btc.wallet.utxo

import io.sourlabs.btc.wallet.models.BalanceInfo
import io.sourlabs.btc.wallet.models.ScriptType
import io.sourlabs.btc.wallet.models.UnspentOutput
import io.sourlabs.btc.wallet.models.confirmations
import io.sourlabs.btc.wallet.storage.BlockInfoStorage
import io.sourlabs.btc.wallet.storage.UnspentOutputStorage
import io.sourlabs.btc.wallet.transactions.FeeCalculator

/**
 * Provides access to unspent outputs and calculates balance.
 *
 * Confirmation counts are derived from the live chain tip in
 * [blockInfoStorage] rather than cached on each UTXO — so as soon as a new
 * block lands, callers see the bumped confirmation count without a re-sync.
 */
class UnspentOutputProvider(
    private val storage: UnspentOutputStorage,
    private val blockInfoStorage: BlockInfoStorage,
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
        val tip = currentTipHeight()
        return storage.getAllUtxos().filter { it.isSpendable(tip) }
    }

    /**
     * Get UTXOs with at least the specified number of confirmations.
     */
    suspend fun getConfirmedUtxos(minConfirmations: Int = confirmationsThreshold): List<UnspentOutput> {
        val tip = currentTipHeight()
        return storage.getAllUtxos().filter { it.confirmations(tip) >= minConfirmations }
    }

    /**
     * Calculate the total balance broken down by spendability.
     */
    suspend fun getBalance(): BalanceInfo {
        val utxos = storage.getAllUtxos()
        val tip = currentTipHeight()

        var spendable = 0L
        var unconfirmed = 0L
        var locked = 0L

        for (utxo in utxos) {
            when {
                !utxo.isSpendable -> locked += utxo.value
                utxo.confirmations(tip) < confirmationsThreshold -> unconfirmed += utxo.value
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
     *
     * Sizes each input against its own [ScriptType] (BIP-44 P2PKH inputs are
     * ~148 vbytes; BIP-49 P2SH-P2WPKH ~91; BIP-84 P2WPKH ~68; BIP-86 P2TR ~58).
     *
     * @param feeRate fee rate in sat/vB
     * @param destinationScriptType script type of the output the funds would
     *   be sent to. Defaults to [ScriptType.P2WPKH]; pass the actual destination
     *   type when known for a precise estimate.
     */
    suspend fun getMaximumSpendable(
        feeRate: Long,
        destinationScriptType: ScriptType = ScriptType.P2WPKH,
    ): Long {
        val spendableUtxos = getSpendableUtxos()
        if (spendableUtxos.isEmpty()) return 0

        val totalValue = spendableUtxos.sumOf { it.value }
        val estimatedFee = FeeCalculator.estimateFee(
            inputs = spendableUtxos.map { it.scriptType },
            outputs = listOf(destinationScriptType),
            feeRateSatPerVb = feeRate,
        )

        return maxOf(0, totalValue - estimatedFee)
    }

    /**
     * Get the total value of all spendable UTXOs.
     */
    suspend fun getSpendableValue(): Long {
        return getSpendableUtxos().sumOf { it.value }
    }

    /**
     * Check if a UTXO is spendable: unlocked, and confirmations against the
     * given tip meet the configured threshold.
     */
    private fun UnspentOutput.isSpendable(tip: Int?): Boolean =
        isSpendable && confirmations(tip) >= confirmationsThreshold

    /**
     * Read the current chain tip from cached block info — null until the
     * first sync completes.
     */
    private suspend fun currentTipHeight(): Int? = blockInfoStorage.getLastBlockInfo()?.height

    /**
     * Check if the wallet has sufficient funds.
     */
    suspend fun hasSufficientFunds(amount: Long, feeRate: Long): Boolean {
        return getMaximumSpendable(feeRate) >= amount
    }
}
