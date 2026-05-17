package io.sourlabs.btc.wallet.storage

/**
 * Combined storage interface providing access to all storage components.
 *
 * Implementations must be safe for concurrent access — the wallet calls into
 * storage from the background sync coroutine and from user-initiated coroutines
 * (e.g. `getBalance()` while a sync is in progress). The built-in
 * [InMemoryWalletStorage] uses a per-sub-storage [kotlinx.coroutines.sync.Mutex]
 * to serialize access; custom database-backed implementations should rely on
 * their backend's transaction or locking semantics for the same guarantee.
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
