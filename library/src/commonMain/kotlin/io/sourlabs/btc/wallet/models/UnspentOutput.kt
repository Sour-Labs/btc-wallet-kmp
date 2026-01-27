package io.sourlabs.btc.wallet.models

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxOut

/**
 * An unspent transaction output (UTXO) that can be used as an input.
 */
data class UnspentOutput(
    /**
     * The transaction hash containing this output.
     */
    val transactionHash: ByteVector32,

    /**
     * The output index within the transaction.
     */
    val outputIndex: Int,

    /**
     * The value in satoshis.
     */
    val value: Long,

    /**
     * The scriptPubKey of this output.
     */
    val scriptPubKey: ByteArray,

    /**
     * The script type of this output.
     */
    val scriptType: ScriptType,

    /**
     * Number of confirmations.
     */
    val confirmations: Int,

    /**
     * The derivation path of the key that controls this output.
     */
    val publicKeyPath: String,

    /**
     * Block height where this output was confirmed (null if unconfirmed).
     */
    val blockHeight: Int? = null,

    /**
     * Whether this output is currently spendable.
     * May be false due to insufficient confirmations, timelocks, or other locks.
     */
    val isSpendable: Boolean = true
) {
    /**
     * Unique identifier for this UTXO: txid:vout
     */
    val id: String
        get() = "${transactionHash.toHex()}:$outputIndex"

    /**
     * Convert to bitcoin-kmp OutPoint.
     */
    fun toOutPoint(): OutPoint = OutPoint(TxId(transactionHash), outputIndex.toLong())

    /**
     * Convert to bitcoin-kmp TxOut.
     */
    fun toTxOut(): TxOut = TxOut(Satoshi(value), ByteVector(scriptPubKey))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as UnspentOutput

        if (transactionHash != other.transactionHash) return false
        if (outputIndex != other.outputIndex) return false
        if (value != other.value) return false
        if (!scriptPubKey.contentEquals(other.scriptPubKey)) return false
        if (scriptType != other.scriptType) return false
        if (confirmations != other.confirmations) return false
        if (publicKeyPath != other.publicKeyPath) return false
        if (blockHeight != other.blockHeight) return false
        if (isSpendable != other.isSpendable) return false

        return true
    }

    override fun hashCode(): Int {
        var result = transactionHash.hashCode()
        result = 31 * result + outputIndex
        result = 31 * result + value.hashCode()
        result = 31 * result + scriptPubKey.contentHashCode()
        result = 31 * result + scriptType.hashCode()
        result = 31 * result + confirmations
        result = 31 * result + publicKeyPath.hashCode()
        result = 31 * result + (blockHeight ?: 0)
        result = 31 * result + isSpendable.hashCode()
        return result
    }
}
