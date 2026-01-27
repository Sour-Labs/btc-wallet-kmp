package io.sourlabs.btc.wallet.storage

import io.sourlabs.btc.wallet.models.BlockInfo

/**
 * Storage interface for blockchain block information.
 */
interface BlockInfoStorage {
    /**
     * Get the last known block info.
     */
    suspend fun getLastBlockInfo(): BlockInfo?

    /**
     * Save block info.
     */
    suspend fun saveBlockInfo(blockInfo: BlockInfo)

    /**
     * Clear stored block info.
     */
    suspend fun clear()
}
