package io.sourlabs.btc.wallet.models

import fr.acinq.bitcoin.Transaction

/**
 * A transaction involving the wallet.
 */
data class WalletTransaction(
    /**
     * Transaction ID (hash in hex).
     */
    val txId: String,

    /**
     * The raw transaction.
     */
    val transaction: Transaction,

    /**
     * Block height where this transaction was confirmed (null if unconfirmed).
     */
    val blockHeight: Int? = null,

    /**
     * Block timestamp in seconds since epoch (null if unconfirmed).
     */
    val timestamp: Long? = null,

    /**
     * Current status of the transaction.
     */
    val status: TransactionStatus,

    /**
     * Type from the wallet's perspective.
     */
    val type: TransactionType,

    /**
     * Net change in satoshis (positive for incoming, negative for outgoing).
     */
    val amount: Long,

    /**
     * Transaction fee in satoshis (null if unknown).
     */
    val fee: Long? = null
) {
    /**
     * Number of confirmations (0 if unconfirmed).
     */
    fun confirmations(currentBlockHeight: Int): Int {
        return blockHeight?.let { currentBlockHeight - it + 1 } ?: 0
    }

    /**
     * Whether this transaction is confirmed.
     */
    val isConfirmed: Boolean
        get() = status == TransactionStatus.CONFIRMED && blockHeight != null

    /**
     * Fee rate in sat/vB (null if fee unknown). Uses the same vSize formula as
     * [io.sourlabs.btc.wallet.transactions.CreatedTransaction.feeRate]:
     * `vSize = (weight + 3) / 4`. The pre-PR-13 form `fee / (weight / 4.0)`
     * was off by ±0.25 vbyte vs the standard integer-rounded vSize.
     */
    val feeRate: Double?
        get() = fee?.let { it.toDouble() / ((transaction.weight() + 3) / 4) }
}
