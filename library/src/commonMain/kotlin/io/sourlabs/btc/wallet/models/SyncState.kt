package io.sourlabs.btc.wallet.models

/**
 * Synchronization state of the wallet.
 */
sealed class SyncState {
    /**
     * Wallet has not started syncing.
     */
    data object NotSynced : SyncState()

    /**
     * Wallet is currently syncing.
     */
    data class Syncing(
        /**
         * Progress from 0.0 to 1.0.
         */
        val progress: Double,

        /**
         * Description of current sync activity.
         */
        val description: String? = null
    ) : SyncState()

    /**
     * Wallet is fully synced.
     */
    data class Synced(
        /**
         * Timestamp of last successful sync.
         */
        val lastSyncTime: Long
    ) : SyncState()

    /**
     * Sync failed with an error.
     */
    data class Error(
        /**
         * Error message.
         */
        val message: String,

        /**
         * The underlying exception if available.
         */
        val cause: Throwable? = null
    ) : SyncState()
}
