package io.sourlabs.btc.wallet.storage

import io.sourlabs.btc.wallet.models.WalletPublicKey

/**
 * Storage interface for public keys.
 */
interface PublicKeyStorage {
    /**
     * Save a public key to storage.
     */
    suspend fun saveKey(key: WalletPublicKey)

    /**
     * Save multiple public keys to storage.
     */
    suspend fun saveKeys(keys: List<WalletPublicKey>)

    /**
     * Get all stored public keys.
     */
    suspend fun getAllKeys(): List<WalletPublicKey>

    /**
     * Get public keys by chain type.
     * @param isExternal true for external (receive) chain, false for internal (change) chain
     */
    suspend fun getKeys(isExternal: Boolean): List<WalletPublicKey>

    /**
     * Get unused public keys by chain type.
     */
    suspend fun getUnusedKeys(isExternal: Boolean): List<WalletPublicKey>

    /**
     * Find a key by its derivation path.
     */
    suspend fun findByPath(path: String): WalletPublicKey?

    /**
     * Find a key by its public key hash (hash160).
     */
    suspend fun findByPublicKeyHash(hash: ByteArray): WalletPublicKey?

    /**
     * Mark a key as used.
     */
    suspend fun markAsUsed(path: String)

    /**
     * Delete all stored keys.
     */
    suspend fun clear()
}
