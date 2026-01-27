package io.sourlabs.btc.wallet.api

import io.sourlabs.btc.wallet.utxo.SelectionStrategy

/**
 * Parameters for sending a transaction.
 */
data class SendParams(
    /**
     * Destination address.
     */
    val toAddress: String,

    /**
     * Amount to send in satoshis.
     */
    val amount: Long,

    /**
     * Fee rate in sat/vB.
     */
    val feeRate: Long,

    /**
     * UTXO selection strategy.
     */
    val strategy: SelectionStrategy = SelectionStrategy.AUTOMATIC,

    /**
     * Whether to enable Replace-By-Fee (BIP125).
     */
    val rbfEnabled: Boolean = true,

    /**
     * Whether to subtract the fee from the send amount.
     */
    val subtractFeeFromAmount: Boolean = false
)
