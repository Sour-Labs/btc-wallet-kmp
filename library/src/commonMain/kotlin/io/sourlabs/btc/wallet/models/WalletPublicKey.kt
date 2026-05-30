package io.sourlabs.btc.wallet.models

import fr.acinq.bitcoin.PublicKey

/**
 * A public key derived from the HD wallet.
 */
data class WalletPublicKey(
    /**
     * Unique identifier - the full derivation path (e.g., "m/84'/0'/0'/0/5")
     */
    val path: String,

    /**
     * The BIP purpose this key was derived for.
     */
    val purpose: Purpose,

    /**
     * Account index in the derivation path.
     */
    val account: Int,

    /**
     * Whether this is an external (receive) address or internal (change) address.
     * External = true (derivation path .../0/index)
     * Internal = false (derivation path .../1/index)
     */
    val isExternal: Boolean,

    /**
     * Index within the chain (external or internal).
     */
    val index: Int,

    /**
     * The compressed public key (33 bytes).
     *
     * For multisig entries this is a representative cosigner pubkey (the first
     * cosigner's derived child key at `chain/index`) — kept non-null so the
     * existing single-key call sites that read it (e.g. signing paths) don't
     * need to special-case multisig. The actual wallet-owned script lives in
     * [scriptPubKey].
     */
    val publicKey: PublicKey,

    /**
     * Hash of the script identifier the chain associates with this key.
     *
     * For single-key entries: hash160 of [publicKey] (RIPEMD160(SHA256(pk))).
     * For multisig entries: SHA-256 of the redeem script (the 32-byte witness
     * program for the P2WSH output) — this is what the chain sees, so it's
     * the right thing to key UTXO and tx lookups on.
     */
    val publicKeyHash: ByteArray,

    /**
     * Whether this key has been used in a transaction.
     */
    val isUsed: Boolean = false,

    /**
     * Last seen confirmed (chain) tx_count for this address. Used by the sync
     * loop to detect deltas without re-fetching the full history.
     */
    val lastSyncedChainTxCount: Int = 0,

    /**
     * Last seen mempool tx_count for this address.
     */
    val lastSyncedMempoolTxCount: Int = 0,

    /**
     * Newest confirmed txid seen at last sync. Acts as a content-addressed
     * cursor for paginated /txs/chain delta fetches; null means no chain
     * history has been recorded yet (cold start or post-eviction).
     */
    val lastSyncedChainTipTxid: String? = null,

    /**
     * Optional override of the on-chain scriptPubKey for this entry.
     *
     * When `null` (the default for single-key BIP-44/49/84/86 entries), the
     * script is derived from [publicKey] + [scriptType] at use time, the way
     * it has always been.
     *
     * When non-null, this is the authoritative scriptPubKey for the entry —
     * used by multisig watch-only wallets where the script is computed up
     * front from N cosigner pubkeys and can't be reconstructed from a single
     * pubkey. [AddressConverter] honours this override when present.
     */
    val scriptPubKey: ByteArray? = null,

    /**
     * Override for [scriptType]. `null` means "derive from [purpose]" — the
     * legacy single-key behaviour ([ScriptType.fromPurpose]). Multisig entries
     * set this to [ScriptType.P2WSH] explicitly because there's no BIP purpose
     * that maps to P2WSH.
     */
    val scriptTypeOverride: ScriptType? = null,
) {
    /**
     * The script type for this entry. For single-key entries this is derived
     * from the BIP purpose ([ScriptType.fromPurpose]); for multisig entries the
     * key source sets [scriptTypeOverride] explicitly.
     */
    val scriptType: ScriptType
        get() = scriptTypeOverride ?: ScriptType.fromPurpose(purpose)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as WalletPublicKey

        if (path != other.path) return false
        if (purpose != other.purpose) return false
        if (account != other.account) return false
        if (isExternal != other.isExternal) return false
        if (index != other.index) return false
        if (publicKey != other.publicKey) return false
        if (!publicKeyHash.contentEquals(other.publicKeyHash)) return false
        if (isUsed != other.isUsed) return false
        if (lastSyncedChainTxCount != other.lastSyncedChainTxCount) return false
        if (lastSyncedMempoolTxCount != other.lastSyncedMempoolTxCount) return false
        if (lastSyncedChainTipTxid != other.lastSyncedChainTipTxid) return false
        if (!(scriptPubKey?.contentEquals(other.scriptPubKey) ?: (other.scriptPubKey == null))) return false
        if (scriptTypeOverride != other.scriptTypeOverride) return false

        return true
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + purpose.hashCode()
        result = 31 * result + account
        result = 31 * result + isExternal.hashCode()
        result = 31 * result + index
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + publicKeyHash.contentHashCode()
        result = 31 * result + isUsed.hashCode()
        result = 31 * result + lastSyncedChainTxCount
        result = 31 * result + lastSyncedMempoolTxCount
        result = 31 * result + (lastSyncedChainTipTxid?.hashCode() ?: 0)
        result = 31 * result + (scriptPubKey?.contentHashCode() ?: 0)
        result = 31 * result + (scriptTypeOverride?.hashCode() ?: 0)
        return result
    }
}
