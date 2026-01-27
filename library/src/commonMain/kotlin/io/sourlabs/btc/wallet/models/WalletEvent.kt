package io.sourlabs.btc.wallet.models

/**
 * Events emitted by the wallet.
 */
sealed class WalletEvent {
    /**
     * Sync state changed.
     */
    data class SyncStateChanged(val state: SyncState) : WalletEvent()

    /**
     * Balance was updated.
     */
    data class BalanceUpdated(val balance: BalanceInfo) : WalletEvent()

    /**
     * New transaction received.
     */
    data class TransactionReceived(val transaction: WalletTransaction) : WalletEvent()

    /**
     * Transaction status changed.
     */
    data class TransactionStatusChanged(
        val txId: String,
        val oldStatus: TransactionStatus,
        val newStatus: TransactionStatus
    ) : WalletEvent()

    /**
     * Transaction was sent successfully.
     */
    data class TransactionSent(val transaction: WalletTransaction) : WalletEvent()

    /**
     * New block was detected.
     */
    data class NewBlock(
        val height: Int,
        val hash: String,
        val timestamp: Long
    ) : WalletEvent()

    /**
     * An error occurred.
     */
    data class WalletError(
        val message: String,
        val cause: Throwable? = null
    ) : WalletEvent()
}
