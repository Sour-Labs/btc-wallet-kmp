package io.sourlabs.btc.wallet.transactions

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Input
import fr.acinq.bitcoin.psbt.Psbt
import io.sourlabs.btc.wallet.api.PsbtException
import kotlin.io.encoding.Base64

/**
 * A transaction signed outside the wallet and checked against the PSBT the
 * wallet built. Only [io.sourlabs.btc.wallet.api.BitcoinKit.finalizeSigned]
 * creates one, so only a checked transaction reaches
 * [io.sourlabs.btc.wallet.api.BitcoinKit.broadcastSigned].
 */
class SignedTransaction internal constructor(
    /**
     * The fully signed transaction.
     */
    val transaction: Transaction,

    /**
     * Fee paid, from the input amounts in the unsigned PSBT.
     */
    val fee: Long
) {
    /**
     * Transaction ID.
     */
    val txId: String
        get() = transaction.txid.toString()
}

/**
 * Turns what a hardware wallet returns into a transaction to broadcast, but
 * only if it is exactly the transaction in the unsigned PSBT the wallet built.
 * Signers return a PSBT, finalized or not, or a raw signed transaction.
 *
 * Amounts and scripts come from the unsigned PSBT the caller kept, never from
 * the returned data, so a signer can't change what is checked.
 */
internal class PsbtFinalizer(
    private val transactionCreator: TransactionCreator,
    private val broadcastRawTransaction: suspend (rawTxHex: String) -> Result<String>
) {
    /**
     * @param unsignedPsbtBase64 the PSBT the wallet built ([PsbtDraft.base64])
     * @param signed what the signer returned, as bytes: a PSBT or a raw transaction
     */
    fun finalize(unsignedPsbtBase64: String, signed: ByteArray): SignedTransaction {
        val unsigned = requireNotNull(Psbt.read(Base64.decode(unsignedPsbtBase64)).right) {
            "unsignedPsbtBase64 is not a PSBT"
        }
        val template = unsigned.global.tx
        val spentOutputs = unsigned.inputs.mapIndexed { index, input ->
            input.witnessUtxo
                ?: input.nonWitnessUtxo?.txOut?.getOrNull(template.txIn[index].outPoint.index.toInt())
                ?: throw IllegalArgumentException("Unsigned PSBT input $index has no UTXO")
        }

        val candidate = if (signed.isPsbt()) {
            val signedPsbt = Psbt.read(signed).right ?: throw PsbtException.UnrecognizedSignedData()
            if (!signedPsbt.global.tx.sameAs(template)) throw PsbtException.SignedTransactionMismatch()
            template.copy(txIn = template.txIn.mapIndexed { index, txIn ->
                txIn.finalizedFrom(signedPsbt.inputs[index], unsigned.inputs[index], spentOutputs[index], index)
            })
        } else {
            val tx = try {
                Transaction.read(signed)
            } catch (e: Exception) {
                throw PsbtException.UnrecognizedSignedData(e)
            }
            if (!tx.sameAs(template)) throw PsbtException.SignedTransactionMismatch()
            tx
        }
        verifySignatures(candidate, spentOutputs)

        val fee = spentOutputs.sumOf { it.amount.sat } - candidate.txOut.sumOf { it.amount.sat }
        return SignedTransaction(candidate, fee)
    }

    /**
     * [finalize] for text: hex (a PSBT or a raw transaction) or base64 (a PSBT).
     */
    fun finalize(unsignedPsbtBase64: String, signed: String): SignedTransaction {
        val text = signed.filterNot { it.isWhitespace() }
        val bytes = try {
            if (text.length % 2 == 0 && text.all { it in HEX_DIGITS }) {
                ByteVector(text).toByteArray()
            } else {
                Base64.decode(text)
            }
        } catch (e: IllegalArgumentException) {
            throw PsbtException.UnrecognizedSignedData(e)
        }
        return finalize(unsignedPsbtBase64, bytes)
    }

    /**
     * Broadcast [signed] and, once the explorer accepts it, record the spend as
     * a signed send does, so balance and history update at once.
     */
    suspend fun broadcast(signed: SignedTransaction): Result<String> {
        val result = broadcastRawTransaction(ByteVector(Transaction.write(signed.transaction)).toHex())
        result.onSuccess { transactionCreator.recordExternallySigned(signed.transaction, signed.fee) }
        return result
    }

    /**
     * This input with the signer's final scripts: copied from a finalized input,
     * or built from the signature on an unfinalized one. The script type and key
     * come from the unsigned PSBT.
     */
    private fun TxIn.finalizedFrom(signedInput: Input, unsignedInput: Input, spent: TxOut, index: Int): TxIn {
        if (signedInput.scriptWitness != null || signedInput.scriptSig != null) {
            return copy(
                signatureScript = signedInput.scriptSig?.let { ByteVector(Script.write(it)) } ?: ByteVector.empty,
                witness = signedInput.scriptWitness ?: ScriptWitness.empty
            )
        }
        val script = spent.publicKeyScript.toByteArray()
        if (Script.isPay2tr(script)) {
            val signature = signedInput.taprootKeySignature ?: throw missingSignature(index)
            return copy(witness = ScriptWitness(listOf(signature)))
        }
        val publicKey = unsignedInput.derivationPaths.keys.single()
        val signature = signedInput.partialSigs[publicKey] ?: throw missingSignature(index)
        return when {
            Script.isPay2wpkh(script) -> copy(witness = ScriptWitness(listOf(signature, publicKey.value)))
            Script.isPay2sh(script) -> copy(
                signatureScript = ByteVector(
                    Script.write(listOf(OP_PUSHDATA(Script.write(requireNotNull(unsignedInput.redeemScript)))))
                ),
                witness = ScriptWitness(listOf(signature, publicKey.value))
            )
            else -> copy(
                signatureScript = ByteVector(Script.write(listOf(OP_PUSHDATA(signature), OP_PUSHDATA(publicKey.value))))
            )
        }
    }

    private fun verifySignatures(tx: Transaction, spentOutputs: List<TxOut>) {
        try {
            tx.correctlySpends(
                tx.txIn.map { it.outPoint }.zip(spentOutputs).toMap(),
                ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS
            )
        } catch (e: Exception) {
            throw PsbtException.SignatureInvalid("Signed transaction fails script verification", e)
        }
        // A valid signature can still leave inputs or outputs out of what it
        // signs (SIGHASH_NONE, SINGLE, ANYONECANPAY): anyone relaying the
        // transaction could then change them.
        tx.txIn.zip(spentOutputs).forEachIndexed { index, (txIn, spent) ->
            val script = spent.publicKeyScript.toByteArray()
            val signature = if (Script.isPay2pkh(script)) {
                (Script.parse(txIn.signatureScript).firstOrNull() as? OP_PUSHDATA)?.data
            } else {
                txIn.witness.stack.firstOrNull()
            }
            val signsAll = when {
                signature == null -> false
                Script.isPay2tr(script) -> signature.size() == 64 ||
                    (signature.size() == 65 && signature[64].toInt() == SigHash.SIGHASH_ALL)
                else -> signature.size() > 0 && signature[signature.size() - 1].toInt() == SigHash.SIGHASH_ALL
            }
            if (!signsAll) throw PsbtException.SignatureInvalid("Input $index is not signed with SIGHASH_ALL")
        }
    }

    private fun missingSignature(index: Int) = PsbtException.SignatureInvalid("Input $index has no signature")
}

private const val HEX_DIGITS = "0123456789abcdefABCDEF"

// BIP-174 magic: "psbt" followed by 0xff.
private val PSBT_MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())

private fun ByteArray.isPsbt(): Boolean =
    size >= PSBT_MAGIC.size && copyOfRange(0, PSBT_MAGIC.size).contentEquals(PSBT_MAGIC)

/**
 * The same transaction apart from signatures: same inputs in the same order
 * with the same sequences, same outputs, same version and lock time.
 */
private fun Transaction.sameAs(other: Transaction): Boolean =
    version == other.version &&
        lockTime == other.lockTime &&
        txIn.map { it.outPoint to it.sequence } == other.txIn.map { it.outPoint to it.sequence } &&
        txOut == other.txOut
