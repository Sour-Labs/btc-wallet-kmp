package io.sourlabs.btc.wallet.storage

import io.sourlabs.btc.wallet.models.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory implementation of wallet storage.
 *
 * Each sub-storage is guarded by its own [Mutex] so concurrent reads/writes from
 * the sync coroutine and user-initiated coroutines (e.g. `getBalance` during a
 * sync) don't observe partially-mutated maps. Useful for testing and short-lived
 * wallet instances.
 */
class InMemoryWalletStorage : WalletStorage {
    override val publicKeyStorage: PublicKeyStorage = InMemoryPublicKeyStorage()
    override val transactionStorage: TransactionStorage = InMemoryTransactionStorage()
    override val unspentOutputStorage: UnspentOutputStorage = InMemoryUnspentOutputStorage()
    override val blockInfoStorage: BlockInfoStorage = InMemoryBlockInfoStorage()
}

private class InMemoryPublicKeyStorage : PublicKeyStorage {
    private val mutex = Mutex()
    private val keys = mutableMapOf<String, WalletPublicKey>()

    override suspend fun saveKey(key: WalletPublicKey): Unit = mutex.withLock {
        keys[key.path] = key
    }

    override suspend fun saveKeys(keys: List<WalletPublicKey>): Unit = mutex.withLock {
        keys.forEach { this.keys[it.path] = it }
    }

    override suspend fun getAllKeys(): List<WalletPublicKey> = mutex.withLock {
        keys.values.toList()
    }

    override suspend fun getKeys(isExternal: Boolean): List<WalletPublicKey> = mutex.withLock {
        keys.values.filter { it.isExternal == isExternal }
    }

    override suspend fun getUnusedKeys(isExternal: Boolean): List<WalletPublicKey> = mutex.withLock {
        keys.values.filter { it.isExternal == isExternal && !it.isUsed }
    }

    override suspend fun findByPath(path: String): WalletPublicKey? = mutex.withLock {
        keys[path]
    }

    override suspend fun findByPublicKeyHash(hash: ByteArray): WalletPublicKey? = mutex.withLock {
        keys.values.find { it.publicKeyHash.contentEquals(hash) }
    }

    override suspend fun markAsUsed(path: String): Unit = mutex.withLock {
        keys[path]?.let { key ->
            keys[path] = key.copy(isUsed = true)
        }
    }

    override suspend fun updateSyncState(
        path: String,
        chainTxCount: Int,
        mempoolTxCount: Int,
        chainTipTxid: String?
    ): Unit = mutex.withLock {
        keys[path]?.let { key ->
            keys[path] = key.copy(
                lastSyncedChainTxCount = chainTxCount,
                lastSyncedMempoolTxCount = mempoolTxCount,
                lastSyncedChainTipTxid = chainTipTxid
            )
        }
    }

    override suspend fun clear(): Unit = mutex.withLock {
        keys.clear()
    }
}

private class InMemoryTransactionStorage : TransactionStorage {
    private val mutex = Mutex()
    private val transactions = mutableMapOf<String, WalletTransaction>()

    override suspend fun saveTransaction(transaction: WalletTransaction): Unit = mutex.withLock {
        transactions[transaction.txId] = transaction
    }

    override suspend fun saveTransactions(transactions: List<WalletTransaction>): Unit = mutex.withLock {
        transactions.forEach { this.transactions[it.txId] = it }
    }

    override suspend fun getTransaction(txId: String): WalletTransaction? = mutex.withLock {
        transactions[txId]
    }

    override suspend fun getTransactions(
        type: TransactionType?,
        limit: Int?,
        offset: Int
    ): List<WalletTransaction> = mutex.withLock {
        var result = transactions.values
            .let { txs -> type?.let { t -> txs.filter { it.type == t } } ?: txs }
            .sortedByDescending { it.timestamp ?: 0 }
            .drop(offset)

        if (limit != null) {
            result = result.take(limit)
        }

        result.toList()
    }

    override suspend fun getTransactionsByStatus(status: TransactionStatus): List<WalletTransaction> = mutex.withLock {
        transactions.values.filter { it.status == status }
    }

    override suspend fun getUnconfirmedTransactions(): List<WalletTransaction> = mutex.withLock {
        transactions.values.filter {
            it.status == TransactionStatus.PENDING || it.status == TransactionStatus.RELAYED
        }
    }

    override suspend fun updateStatus(txId: String, status: TransactionStatus): Unit = mutex.withLock {
        transactions[txId]?.let { tx ->
            transactions[txId] = tx.copy(status = status)
        }
    }

    override suspend fun updateConfirmation(txId: String, blockHeight: Int, timestamp: Long): Unit = mutex.withLock {
        transactions[txId]?.let { tx ->
            transactions[txId] = tx.copy(
                blockHeight = blockHeight,
                timestamp = timestamp,
                status = TransactionStatus.CONFIRMED
            )
        }
    }

    override suspend fun exists(txId: String): Boolean = mutex.withLock {
        transactions.containsKey(txId)
    }

    override suspend fun deleteTransaction(txId: String): Unit = mutex.withLock {
        transactions.remove(txId)
    }

    override suspend fun clear(): Unit = mutex.withLock {
        transactions.clear()
    }
}

private class InMemoryUnspentOutputStorage : UnspentOutputStorage {
    private val mutex = Mutex()
    private val utxos = mutableMapOf<String, UnspentOutput>()

    override suspend fun saveUtxo(utxo: UnspentOutput): Unit = mutex.withLock {
        utxos[utxo.id] = utxo
    }

    override suspend fun saveUtxos(utxos: List<UnspentOutput>): Unit = mutex.withLock {
        utxos.forEach { this.utxos[it.id] = it }
    }

    override suspend fun getUtxo(id: String): UnspentOutput? = mutex.withLock {
        utxos[id]
    }

    override suspend fun getAllUtxos(): List<UnspentOutput> = mutex.withLock {
        utxos.values.toList()
    }

    override suspend fun getSpendableUtxos(): List<UnspentOutput> = mutex.withLock {
        utxos.values.filter { it.isSpendable }
    }

    override suspend fun getUtxosForKey(publicKeyPath: String): List<UnspentOutput> = mutex.withLock {
        utxos.values.filter { it.publicKeyPath == publicKeyPath }
    }

    override suspend fun updateSpendability(id: String, isSpendable: Boolean): Unit = mutex.withLock {
        utxos[id]?.let { utxo ->
            utxos[id] = utxo.copy(isSpendable = isSpendable)
        }
    }

    override suspend fun deleteUtxo(id: String): Unit = mutex.withLock {
        utxos.remove(id)
    }

    override suspend fun deleteUtxos(ids: List<String>): Unit = mutex.withLock {
        ids.forEach { utxos.remove(it) }
    }

    override suspend fun exists(id: String): Boolean = mutex.withLock {
        utxos.containsKey(id)
    }

    override suspend fun clear(): Unit = mutex.withLock {
        utxos.clear()
    }
}

private class InMemoryBlockInfoStorage : BlockInfoStorage {
    private val mutex = Mutex()
    private var lastBlock: BlockInfo? = null

    override suspend fun getLastBlockInfo(): BlockInfo? = mutex.withLock {
        lastBlock
    }

    override suspend fun saveBlockInfo(blockInfo: BlockInfo): Unit = mutex.withLock {
        lastBlock = blockInfo
    }

    override suspend fun clear(): Unit = mutex.withLock {
        lastBlock = null
    }
}
