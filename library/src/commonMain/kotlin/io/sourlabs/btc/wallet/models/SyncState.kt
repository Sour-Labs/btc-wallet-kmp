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
        val lastSyncTime: Long,

        /**
         * Warnings about sync configurations that failed before a fallback succeeded.
         * Empty when the primary config worked without issues.
         */
        val warnings: List<Warning> = emptyList()
    ) : SyncState() {
        /**
         * True if one or more sync configurations failed before a fallback succeeded.
         */
        val usedFallback: Boolean get() = warnings.isNotEmpty()
    }

    /**
     * A sync configuration that failed during fallback.
     */
    data class Warning(
        /**
         * Simple name of the SyncConfig that failed (e.g. "MempoolSpace", "BlockStream").
         */
        val configName: String,

        /**
         * Description of what went wrong.
         */
        val message: String,

        /**
         * The underlying exception if available.
         */
        val cause: Throwable? = null
    )

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
