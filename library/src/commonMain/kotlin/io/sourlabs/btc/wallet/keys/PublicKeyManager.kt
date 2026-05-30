package io.sourlabs.btc.wallet.keys

import io.sourlabs.btc.wallet.models.WalletPublicKey
import io.sourlabs.btc.wallet.storage.PublicKeyStorage

/**
 * Manages the pool of public keys for the wallet with gap limit support.
 * Ensures there are always [gapLimit] unused keys available in both
 * external (receive) and internal (change) chains.
 *
 * The actual derivation strategy — single-key BIP-44/49/84/86 or multisig
 * `wsh(sortedmulti)` — is injected via [keySource]. The gap-limit scanner here
 * doesn't care which one is active: it just asks the source for the next key
 * and stores whatever [WalletPublicKey] comes back.
 */
class PublicKeyManager(
    private val keySource: WalletKeySource,
    private val storage: PublicKeyStorage,
    private val gapLimit: Int = 20
) {
    init {
        require(gapLimit > 0) { "Gap limit must be positive" }
    }

    /**
     * Initialize the key pool, filling to gap limit if needed.
     */
    suspend fun initialize() {
        fillGap(isExternal = true)
        fillGap(isExternal = false)
    }

    /**
     * Get a fresh receive (external) address key.
     * Returns the first unused key, or generates new keys if needed.
     */
    suspend fun getReceivePublicKey(): WalletPublicKey {
        return getNextUnusedKey(isExternal = true)
    }

    /**
     * Get a fresh change (internal) address key.
     * Returns the first unused key, or generates new keys if needed.
     */
    suspend fun getChangePublicKey(): WalletPublicKey {
        return getNextUnusedKey(isExternal = false)
    }

    /**
     * Get all public keys (both external and internal).
     */
    suspend fun getAllPublicKeys(): List<WalletPublicKey> {
        return storage.getAllKeys()
    }

    /**
     * Get all external (receive) public keys.
     */
    suspend fun getExternalPublicKeys(): List<WalletPublicKey> {
        return storage.getKeys(isExternal = true)
    }

    /**
     * Get all internal (change) public keys.
     */
    suspend fun getInternalPublicKeys(): List<WalletPublicKey> {
        return storage.getKeys(isExternal = false)
    }

    /**
     * Find a public key by its hash160.
     */
    suspend fun findByPublicKeyHash(hash: ByteArray): WalletPublicKey? {
        return storage.findByPublicKeyHash(hash)
    }

    /**
     * Find a public key by its derivation path.
     */
    suspend fun findByPath(path: String): WalletPublicKey? {
        return storage.findByPath(path)
    }

    /**
     * Mark a key as used and ensure gap limit is maintained.
     */
    suspend fun markAsUsed(path: String) {
        val key = storage.findByPath(path) ?: return
        if (!key.isUsed) {
            storage.markAsUsed(path)
            fillGap(isExternal = key.isExternal)
        }
    }

    /**
     * Persist the per-address sync state used by the delta-fetch algorithm.
     */
    suspend fun recordSyncState(
        path: String,
        chainTxCount: Int,
        mempoolTxCount: Int,
        chainTipTxid: String?
    ) {
        storage.updateSyncState(path, chainTxCount, mempoolTxCount, chainTipTxid)
    }

    /**
     * Get the count of unused keys in a chain.
     */
    suspend fun unusedKeyCount(isExternal: Boolean): Int {
        return storage.getUnusedKeys(isExternal).size
    }

    /**
     * Derive keys up to a specific index (used for wallet restoration).
     * @param maxIndex the maximum index to derive to (inclusive)
     */
    suspend fun deriveKeysUpTo(maxIndex: Int, isExternal: Boolean) {
        val existingKeys = storage.getKeys(isExternal)
        val currentMaxIndex = existingKeys.maxOfOrNull { it.index } ?: -1

        for (index in (currentMaxIndex + 1)..maxIndex) {
            val key = deriveKey(isExternal, index)
            storage.saveKey(key)
        }
    }

    private suspend fun getNextUnusedKey(isExternal: Boolean): WalletPublicKey {
        val unusedKeys = storage.getUnusedKeys(isExternal)
        if (unusedKeys.isNotEmpty()) {
            return unusedKeys.minByOrNull { it.index }!!
        }

        // No unused keys, derive a new one
        fillGap(isExternal)
        return storage.getUnusedKeys(isExternal).minByOrNull { it.index }!!
    }

    private suspend fun fillGap(isExternal: Boolean) {
        val unusedCount = storage.getUnusedKeys(isExternal).size
        if (unusedCount >= gapLimit) return

        val existingKeys = storage.getKeys(isExternal)
        val startIndex = (existingKeys.maxOfOrNull { it.index } ?: -1) + 1
        val keysToGenerate = gapLimit - unusedCount

        for (i in 0 until keysToGenerate) {
            val index = startIndex + i
            val key = deriveKey(isExternal, index)
            storage.saveKey(key)
        }
    }

    private fun deriveKey(isExternal: Boolean, index: Int): WalletPublicKey =
        keySource.deriveKey(isExternal, index)
}
