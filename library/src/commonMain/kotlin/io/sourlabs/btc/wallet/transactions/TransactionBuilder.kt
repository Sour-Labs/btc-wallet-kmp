package io.sourlabs.btc.wallet.transactions

import fr.acinq.bitcoin.*
import io.sourlabs.btc.wallet.keys.AddressConverter
import io.sourlabs.btc.wallet.models.ScriptType
import io.sourlabs.btc.wallet.models.UnspentOutput
import io.sourlabs.btc.wallet.models.WalletPublicKey
import io.sourlabs.btc.wallet.utxo.SelectionResult

/**
 * Parameters for building a transaction.
 */
data class TransactionParams(
    /**
     * Destination address.
     */
    val toAddress: String,

    /**
     * Amount to send in satoshis.
     */
    val amount: Long,

    /**
     * Fee rate in sat/vB.
     */
    val feeRate: Long,

    /**
     * Whether to enable Replace-By-Fee (BIP125).
     */
    val rbfEnabled: Boolean = true,

    /**
     * Whether to subtract fee from the send amount.
     */
    val subtractFeeFromAmount: Boolean = false,

    /**
     * Custom locktime (default 0).
     */
    val lockTime: Long = 0,

    /**
     * Custom memo/OP_RETURN data (optional).
     */
    val memo: ByteArray? = null
)

/**
 * An unsigned transaction ready for signing.
 */
data class UnsignedTransaction(
    /**
     * The transaction template.
     */
    val transaction: Transaction,

    /**
     * UTXOs being spent (in input order).
     */
    val utxos: List<UnspentOutput>,

    /**
     * Public keys for each input (in input order).
     */
    val publicKeys: List<WalletPublicKey>,

    /**
     * Whether there's a change output.
     */
    val hasChange: Boolean,

    /**
     * Change output index (null if no change).
     */
    val changeOutputIndex: Int?,

    /**
     * Total fee in satoshis.
     */
    val fee: Long
)

/**
 * Builds unsigned Bitcoin transactions.
 */
class TransactionBuilder(
    private val addressConverter: AddressConverter
) {
    /**
     * Build an unsigned transaction.
     *
     * @param selectionResult the UTXO selection result
     * @param params transaction parameters
     * @param changeKey public key for change output
     * @param inputKeys public keys for each selected UTXO (in order)
     */
    fun build(
        selectionResult: SelectionResult,
        params: TransactionParams,
        changeKey: WalletPublicKey?,
        inputKeys: List<WalletPublicKey>
    ): UnsignedTransaction {
        require(selectionResult.selectedUtxos.size == inputKeys.size) {
            "Number of input keys must match number of selected UTXOs"
        }

        // Build inputs
        val inputs = selectionResult.selectedUtxos.mapIndexed { index, utxo ->
            val sequence = if (params.rbfEnabled) {
                0xFFFFFFFDu // Enable RBF (BIP125)
            } else {
                0xFFFFFFFEu // Final, but allow locktime
            }

            TxIn(
                outPoint = utxo.toOutPoint(),
                signatureScript = ByteVector.empty, // Will be filled during signing
                sequence = sequence.toLong()
            )
        }

        // Build outputs
        val outputs = mutableListOf<TxOut>()

        // The selection is authoritative on amounts: SelectionResult.sendAmount
        // already reflects subtract-fee semantics (the selector computed
        // targetAmount − fee). Re-deriving it here from params.amount − fee
        // would double-count the fee when the selector absorbed a sub-dust
        // residual into it.
        val sendAmount = selectionResult.sendAmount
        require(sendAmount > 0) {
            "Destination amount must be positive, got $sendAmount sats (fee exceeds the send amount?)"
        }

        // Add destination output
        val destinationScript = addressConverter.addressToScriptPubKey(params.toAddress)
            ?: throw IllegalArgumentException("Invalid destination address: ${params.toAddress}")
        outputs.add(TxOut(Satoshi(sendAmount), ByteVector(destinationScript)))

        // Add change output if needed
        var changeOutputIndex: Int? = null
        if (selectionResult.hasChange && changeKey != null) {
            val changeScript = addressConverter.createScriptPubKey(
                changeKey.publicKey,
                changeKey.scriptType
            )
            val changeAmount = selectionResult.totalInput - sendAmount - selectionResult.fee
            outputs.add(TxOut(Satoshi(changeAmount), ByteVector(changeScript)))
            changeOutputIndex = outputs.size - 1
        }

        // Add OP_RETURN output if memo provided
        if (params.memo != null && params.memo.isNotEmpty()) {
            val opReturnScript = listOf(
                OP_RETURN,
                OP_PUSHDATA(ByteVector(params.memo))
            )
            outputs.add(TxOut(Satoshi(0), ByteVector(Script.write(opReturnScript))))
        }

        // Build transaction
        val transaction = Transaction(
            version = 2,
            txIn = inputs,
            txOut = outputs,
            lockTime = params.lockTime
        )

        return UnsignedTransaction(
            transaction = transaction,
            utxos = selectionResult.selectedUtxos,
            publicKeys = inputKeys,
            hasChange = selectionResult.hasChange,
            changeOutputIndex = changeOutputIndex,
            fee = selectionResult.fee
        )
    }

    /**
     * Build a transaction for sending maximum funds (sweep).
     */
    fun buildSweep(
        utxos: List<UnspentOutput>,
        toAddress: String,
        feeRate: Long,
        inputKeys: List<WalletPublicKey>,
        rbfEnabled: Boolean = true
    ): UnsignedTransaction {
        require(utxos.size == inputKeys.size) {
            "Number of input keys must match number of UTXOs"
        }

        val totalInput = utxos.sumOf { it.value }

        // Estimate fee using each input's actual script type and the destination's
        // own script type, instead of assuming P2WPKH for everything.
        val destinationScriptType = addressConverter.parseAddress(toAddress)?.scriptType
            ?: throw IllegalArgumentException("Invalid destination address: $toAddress")
        val fee = FeeCalculator.estimateFee(
            inputs = inputKeys.map { it.scriptType },
            outputs = listOf(destinationScriptType),
            feeRateSatPerVb = feeRate,
        )
        val sendAmount = totalInput - fee

        require(sendAmount > 0) { "Insufficient funds after fees" }

        // Build inputs
        val inputs = utxos.map { utxo ->
            val sequence = if (rbfEnabled) 0xFFFFFFFDu else 0xFFFFFFFEu
            TxIn(
                outPoint = utxo.toOutPoint(),
                signatureScript = ByteVector.empty,
                sequence = sequence.toLong()
            )
        }

        // Build single output
        val destinationScript = addressConverter.addressToScriptPubKey(toAddress)
            ?: throw IllegalArgumentException("Invalid destination address: $toAddress")
        val outputs = listOf(TxOut(Satoshi(sendAmount), ByteVector(destinationScript)))

        val transaction = Transaction(
            version = 2,
            txIn = inputs,
            txOut = outputs,
            lockTime = 0
        )

        return UnsignedTransaction(
            transaction = transaction,
            utxos = utxos,
            publicKeys = inputKeys,
            hasChange = false,
            changeOutputIndex = null,
            fee = fee
        )
    }

}
