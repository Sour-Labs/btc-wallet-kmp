package io.sourlabs.btc.wallet.storage

/**
 * Combined storage interface providing access to all storage components.
 */
interface WalletStorage {
    val publicKeyStorage: PublicKeyStorage
    val transactionStorage: TransactionStorage
    val unspentOutputStorage: UnspentOutputStorage
    val blockInfoStorage: BlockInfoStorage

    /**
     * Clear all storage.
     */
    suspend fun clearAll() {
        publicKeyStorage.clear()
        transactionStorage.clear()
        unspentOutputStorage.clear()
        blockInfoStorage.clear()
    }
}
