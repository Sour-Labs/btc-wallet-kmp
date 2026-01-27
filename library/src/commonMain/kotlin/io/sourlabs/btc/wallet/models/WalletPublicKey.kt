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
     */
    val publicKey: PublicKey,

    /**
     * Hash160 of the public key (RIPEMD160(SHA256(publicKey))).
     */
    val publicKeyHash: ByteArray,

    /**
     * Whether this key has been used in a transaction.
     */
    val isUsed: Boolean = false
) {
    /**
     * The script type derived from the purpose.
     */
    val scriptType: ScriptType
        get() = ScriptType.fromPurpose(purpose)

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
        return result
    }
}
