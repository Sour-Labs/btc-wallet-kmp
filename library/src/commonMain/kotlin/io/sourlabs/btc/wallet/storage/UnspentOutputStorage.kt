package io.sourlabs.btc.wallet.storage

import io.sourlabs.btc.wallet.models.UnspentOutput

/**
 * Storage interface for unspent transaction outputs (UTXOs).
 */
interface UnspentOutputStorage {
    /**
     * Save a UTXO to storage.
     */
    suspend fun saveUtxo(utxo: UnspentOutput)

    /**
     * Save multiple UTXOs to storage.
     */
    suspend fun saveUtxos(utxos: List<UnspentOutput>)

    /**
     * Get a UTXO by its ID (txid:vout).
     */
    suspend fun getUtxo(id: String): UnspentOutput?

    /**
     * Get all UTXOs.
     */
    suspend fun getAllUtxos(): List<UnspentOutput>

    /**
     * Get spendable UTXOs.
     */
    suspend fun getSpendableUtxos(): List<UnspentOutput>

    /**
     * Get UTXOs for a specific public key path.
     */
    suspend fun getUtxosForKey(publicKeyPath: String): List<UnspentOutput>

    /**
     * Update UTXO confirmation count.
     */
    suspend fun updateConfirmations(id: String, confirmations: Int, blockHeight: Int?)

    /**
     * Update UTXO spendability.
     */
    suspend fun updateSpendability(id: String, isSpendable: Boolean)

    /**
     * Delete a UTXO (when spent).
     */
    suspend fun deleteUtxo(id: String)

    /**
     * Delete multiple UTXOs.
     */
    suspend fun deleteUtxos(ids: List<String>)

    /**
     * Check if a UTXO exists.
     */
    suspend fun exists(id: String): Boolean

    /**
     * Delete all UTXOs.
     */
    suspend fun clear()
}
