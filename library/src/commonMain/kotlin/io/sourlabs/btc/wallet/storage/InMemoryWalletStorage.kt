package io.sourlabs.btc.wallet.storage

import io.sourlabs.btc.wallet.models.*

/**
 * In-memory implementation of wallet storage.
 * Useful for testing and short-lived wallet instances.
 */
class InMemoryWalletStorage : WalletStorage {
    override val publicKeyStorage: PublicKeyStorage = InMemoryPublicKeyStorage()
    override val transactionStorage: TransactionStorage = InMemoryTransactionStorage()
    override val unspentOutputStorage: UnspentOutputStorage = InMemoryUnspentOutputStorage()
    override val blockInfoStorage: BlockInfoStorage = InMemoryBlockInfoStorage()
}

private class InMemoryPublicKeyStorage : PublicKeyStorage {
    private val keys = mutableMapOf<String, WalletPublicKey>()

    override suspend fun saveKey(key: WalletPublicKey) {
        keys[key.path] = key
    }

    override suspend fun saveKeys(keys: List<WalletPublicKey>) {
        keys.forEach { saveKey(it) }
    }

    override suspend fun getAllKeys(): List<WalletPublicKey> {
        return keys.values.toList()
    }

    override suspend fun getKeys(isExternal: Boolean): List<WalletPublicKey> {
        return keys.values.filter { it.isExternal == isExternal }
    }

    override suspend fun getUnusedKeys(isExternal: Boolean): List<WalletPublicKey> {
        return keys.values.filter { it.isExternal == isExternal && !it.isUsed }
    }

    override suspend fun findByPath(path: String): WalletPublicKey? {
        return keys[path]
    }

    override suspend fun findByPublicKeyHash(hash: ByteArray): WalletPublicKey? {
        return keys.values.find { it.publicKeyHash.contentEquals(hash) }
    }

    override suspend fun markAsUsed(path: String) {
        keys[path]?.let { key ->
            keys[path] = key.copy(isUsed = true)
        }
    }

    override suspend fun updateSyncState(
        path: String,
        chainTxCount: Int,
        mempoolTxCount: Int,
        chainTipTxid: String?
    ) {
        keys[path]?.let { key ->
            keys[path] = key.copy(
                lastSyncedChainTxCount = chainTxCount,
                lastSyncedMempoolTxCount = mempoolTxCount,
                lastSyncedChainTipTxid = chainTipTxid
            )
        }
    }

    override suspend fun clear() {
        keys.clear()
    }
}

private class InMemoryTransactionStorage : TransactionStorage {
    private val transactions = mutableMapOf<String, WalletTransaction>()

    override suspend fun saveTransaction(transaction: WalletTransaction) {
        transactions[transaction.txId] = transaction
    }

    override suspend fun saveTransactions(transactions: List<WalletTransaction>) {
        transactions.forEach { saveTransaction(it) }
    }

    override suspend fun getTransaction(txId: String): WalletTransaction? {
        return transactions[txId]
    }

    override suspend fun getTransactions(
        type: TransactionType?,
        limit: Int?,
        offset: Int
    ): List<WalletTransaction> {
        var result = transactions.values
            .let { txs -> type?.let { t -> txs.filter { it.type == t } } ?: txs }
            .sortedByDescending { it.timestamp ?: 0 }
            .drop(offset)

        if (limit != null) {
            result = result.take(limit)
        }

        return result.toList()
    }

    override suspend fun getTransactionsByStatus(status: TransactionStatus): List<WalletTransaction> {
        return transactions.values.filter { it.status == status }
    }

    override suspend fun getUnconfirmedTransactions(): List<WalletTransaction> {
        return transactions.values.filter {
            it.status == TransactionStatus.PENDING || it.status == TransactionStatus.RELAYED
        }
    }

    override suspend fun updateStatus(txId: String, status: TransactionStatus) {
        transactions[txId]?.let { tx ->
            transactions[txId] = tx.copy(status = status)
        }
    }

    override suspend fun updateConfirmation(txId: String, blockHeight: Int, timestamp: Long) {
        transactions[txId]?.let { tx ->
            transactions[txId] = tx.copy(
                blockHeight = blockHeight,
                timestamp = timestamp,
                status = TransactionStatus.CONFIRMED
            )
        }
    }

    override suspend fun exists(txId: String): Boolean {
        return transactions.containsKey(txId)
    }

    override suspend fun deleteTransaction(txId: String) {
        transactions.remove(txId)
    }

    override suspend fun clear() {
        transactions.clear()
    }
}

private class InMemoryUnspentOutputStorage : UnspentOutputStorage {
    private val utxos = mutableMapOf<String, UnspentOutput>()

    override suspend fun saveUtxo(utxo: UnspentOutput) {
        utxos[utxo.id] = utxo
    }

    override suspend fun saveUtxos(utxos: List<UnspentOutput>) {
        utxos.forEach { saveUtxo(it) }
    }

    override suspend fun getUtxo(id: String): UnspentOutput? {
        return utxos[id]
    }

    override suspend fun getAllUtxos(): List<UnspentOutput> {
        return utxos.values.toList()
    }

    override suspend fun getSpendableUtxos(): List<UnspentOutput> {
        return utxos.values.filter { it.isSpendable }
    }

    override suspend fun getUtxosForKey(publicKeyPath: String): List<UnspentOutput> {
        return utxos.values.filter { it.publicKeyPath == publicKeyPath }
    }

    override suspend fun updateConfirmations(id: String, confirmations: Int, blockHeight: Int?) {
        utxos[id]?.let { utxo ->
            utxos[id] = utxo.copy(confirmations = confirmations, blockHeight = blockHeight)
        }
    }

    override suspend fun updateSpendability(id: String, isSpendable: Boolean) {
        utxos[id]?.let { utxo ->
            utxos[id] = utxo.copy(isSpendable = isSpendable)
        }
    }

    override suspend fun deleteUtxo(id: String) {
        utxos.remove(id)
    }

    override suspend fun deleteUtxos(ids: List<String>) {
        ids.forEach { utxos.remove(it) }
    }

    override suspend fun exists(id: String): Boolean {
        return utxos.containsKey(id)
    }

    override suspend fun clear() {
        utxos.clear()
    }
}

private class InMemoryBlockInfoStorage : BlockInfoStorage {
    private var lastBlock: BlockInfo? = null

    override suspend fun getLastBlockInfo(): BlockInfo? {
        return lastBlock
    }

    override suspend fun saveBlockInfo(blockInfo: BlockInfo) {
        lastBlock = blockInfo
    }

    override suspend fun clear() {
        lastBlock = null
    }
}
