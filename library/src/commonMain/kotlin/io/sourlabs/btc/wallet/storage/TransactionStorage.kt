package io.sourlabs.btc.wallet.storage

import io.sourlabs.btc.wallet.models.TransactionStatus
import io.sourlabs.btc.wallet.models.TransactionType
import io.sourlabs.btc.wallet.models.WalletTransaction

/**
 * Storage interface for wallet transactions.
 */
interface TransactionStorage {
    /**
     * Save a transaction to storage.
     */
    suspend fun saveTransaction(transaction: WalletTransaction)

    /**
     * Save multiple transactions to storage.
     */
    suspend fun saveTransactions(transactions: List<WalletTransaction>)

    /**
     * Get a transaction by its ID.
     */
    suspend fun getTransaction(txId: String): WalletTransaction?

    /**
     * Get all transactions, optionally filtered by type.
     * @param type optional filter by transaction type
     * @param limit maximum number of transactions to return
     * @param offset number of transactions to skip
     */
    suspend fun getTransactions(
        type: TransactionType? = null,
        limit: Int? = null,
        offset: Int = 0
    ): List<WalletTransaction>

    /**
     * Get transactions by status.
     */
    suspend fun getTransactionsByStatus(status: TransactionStatus): List<WalletTransaction>

    /**
     * Get unconfirmed transactions (pending or relayed).
     */
    suspend fun getUnconfirmedTransactions(): List<WalletTransaction>

    /**
     * Update the status of a transaction.
     */
    suspend fun updateStatus(txId: String, status: TransactionStatus)

    /**
     * Update transaction confirmation info.
     */
    suspend fun updateConfirmation(txId: String, blockHeight: Int, timestamp: Long)

    /**
     * Check if a transaction exists.
     */
    suspend fun exists(txId: String): Boolean

    /**
     * Delete a transaction.
     */
    suspend fun deleteTransaction(txId: String)

    /**
     * Delete all transactions.
     */
    suspend fun clear()
}
