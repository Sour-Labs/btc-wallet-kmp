package io.sourlabs.btc.wallet.transactions

import co.touchlab.kermit.Logger
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.psbt.Input
import fr.acinq.bitcoin.psbt.Psbt
import io.sourlabs.btc.wallet.api.PsbtException
import io.sourlabs.btc.wallet.api.PsbtException.SignedTransactionMismatch.Part
import io.sourlabs.btc.wallet.core.WalletConfig
import io.sourlabs.btc.wallet.models.ScriptType
import io.sourlabs.btc.wallet.models.UnspentOutput
import io.sourlabs.btc.wallet.models.WalletPublicKey
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

private val log = Logger.withTag("PsbtFinalizer")

/**
 * A transaction signed outside the wallet and checked against the [PsbtDraft]
 * the wallet built. Only [io.sourlabs.btc.wallet.api.BitcoinKit.finalizeSigned]
 * creates one, so only a checked transaction reaches
 * [io.sourlabs.btc.wallet.api.BitcoinKit.broadcastSigned].
 */
class SignedTransaction internal constructor(
    /**
     * The fully signed transaction.
     */
    val transaction: Transaction,

    /**
     * Fee paid, from the amounts of the UTXOs the draft spends.
     */
    val fee: Long,

    internal val draft: PsbtDraft
) {
    /**
     * Transaction ID.
     */
    val txId: String
        get() = transaction.txid.toString()
}

/**
 * Turns what a hardware wallet returns into a transaction to broadcast, but
 * only if it is exactly the transaction in the [PsbtDraft] the wallet built.
 * Signers return a PSBT, finalized or not, or a raw signed transaction.
 *
 * Amounts, scripts and keys come from the draft, which only
 * [PsbtCreator] makes, never from what the signer returned.
 */
internal class PsbtFinalizer(
    private val walletConfig: WalletConfig,
    private val transactionCreator: TransactionCreator,
    private val broadcastRawTransaction: suspend (rawTxHex: String) -> Result<String>
) {
    /**
     * @param signed what the signer returned: a PSBT or a raw transaction, as
     *   binary or as text (base64 or hex), e.g. a BBQr payload or a file
     */
    fun finalize(draft: PsbtDraft, signed: ByteArray): SignedTransaction =
        finalizeBinary(draft, signed.asBinary())

    /**
     * [finalize] for text: a PSBT in base64 or hex, or a raw transaction in hex.
     */
    fun finalize(draft: PsbtDraft, signed: String): SignedTransaction =
        finalizeBinary(draft, decodeText(signed) ?: throw PsbtException.UnrecognizedSignedData())

    /**
     * Broadcast [signed] and, once the explorer accepts it, record the spend as
     * a signed send does, so balance and history update at once.
     */
    suspend fun broadcast(signed: SignedTransaction): Result<String> {
        if (signed.draft.walletConfig != walletConfig) throw PsbtException.OtherWallet()
        val result = broadcastRawTransaction(ByteVector(Transaction.write(signed.transaction)).toHex())
        if (result.isSuccess) {
            // The explorer has it now: a failed or cancelled record must not turn
            // that into a failure the caller would report as "not sent" or retry.
            withContext(NonCancellable) {
                try {
                    transactionCreator.recordExternallySigned(signed)
                } catch (e: Exception) {
                    log.e(e) { "Broadcast ${signed.txId} but could not record it; the next sync will" }
                }
            }
        }
        return result
    }

    private fun finalizeBinary(draft: PsbtDraft, bytes: ByteArray): SignedTransaction {
        if (draft.walletConfig != walletConfig) throw PsbtException.OtherWallet()
        val template = draft.psbt.global.tx
        val candidate = if (bytes.isPsbt()) {
            val signedPsbt = readPsbt(bytes)
            template.requireSameAs(signedPsbt.global.tx)
            template.copy(txIn = template.txIn.mapIndexed { index, txIn ->
                txIn.finalizedFrom(signedPsbt.inputs[index], draft.inputKeys[index], index)
            })
        } else {
            readTransaction(bytes).also { template.requireSameAs(it) }
        }
        verifySignatures(candidate, draft.spentUtxos, draft.inputKeys)
        return SignedTransaction(
            transaction = candidate,
            fee = draft.spentUtxos.sumOf { it.value } - candidate.txOut.sumOf { it.amount.sat },
            draft = draft
        )
    }

    /**
     * This input with the signer's final scripts: copied from a finalized input,
     * or built from the signature on an unfinalized one for the wallet's [key].
     */
    private fun TxIn.finalizedFrom(signedInput: Input, key: WalletPublicKey, index: Int): TxIn {
        if (signedInput.scriptWitness != null || signedInput.scriptSig != null) {
            return copy(
                signatureScript = signedInput.scriptSig?.let { ByteVector(Script.write(it)) } ?: ByteVector.empty,
                witness = signedInput.scriptWitness ?: ScriptWitness.empty
            )
        }
        if (key.scriptType == ScriptType.P2TR) {
            val signature = signedInput.taprootKeySignature ?: throw missingSignature(index)
            return copy(witness = ScriptWitness(listOf(signature)))
        }
        val signature = signedInput.partialSigs[key.publicKey] ?: throw missingSignature(index)
        return when (key.scriptType) {
            ScriptType.P2WPKH -> copy(witness = ScriptWitness(listOf(signature, key.publicKey.value)))
            ScriptType.P2SH_P2WPKH -> copy(
                signatureScript = ByteVector(Script.write(listOf(OP_PUSHDATA(Script.write(Script.pay2wpkh(key.publicKey)))))),
                witness = ScriptWitness(listOf(signature, key.publicKey.value))
            )
            ScriptType.P2PKH -> copy(
                signatureScript = ByteVector(Script.write(listOf(OP_PUSHDATA(signature), OP_PUSHDATA(key.publicKey.value))))
            )
            // A draft only comes from PsbtCreator, which refuses multisig wallets.
            ScriptType.P2TR, ScriptType.P2SH, ScriptType.P2WSH -> error("${key.scriptType} input in a single-key wallet")
        }
    }

    private fun verifySignatures(tx: Transaction, spentUtxos: List<UnspentOutput>, inputKeys: List<WalletPublicKey>) {
        try {
            tx.verifySpends(spentUtxos)
        } catch (e: Exception) {
            throw PsbtException.SignatureInvalid("Signed transaction fails script verification", e)
        }
        // A valid signature can still leave inputs or outputs out of what it
        // signs (SIGHASH_NONE, SINGLE, ANYONECANPAY): anyone relaying the
        // transaction could then change them.
        tx.txIn.zip(inputKeys).forEachIndexed { index, (txIn, key) ->
            val signature = if (key.scriptType == ScriptType.P2PKH) {
                (Script.parse(txIn.signatureScript).firstOrNull() as? OP_PUSHDATA)?.data
            } else {
                txIn.witness.stack.firstOrNull()
            }
            val signsAll = when {
                signature == null -> false
                key.scriptType == ScriptType.P2TR -> signature.size() == 64 ||
                    (signature.size() == 65 && signature[64].toInt() == SigHash.SIGHASH_ALL)
                else -> signature.size() > 0 && signature[signature.size() - 1].toInt() == SigHash.SIGHASH_ALL
            }
            if (!signsAll) throw PsbtException.SignatureInvalid("Input $index is not signed with SIGHASH_ALL")
        }
    }

    private fun missingSignature(index: Int) = PsbtException.SignatureInvalid("Input $index has no signature")
}

private const val HEX_DIGITS = "0123456789abcdefABCDEF"

// Signers and relays differ: padding may be stripped, and the URL-safe
// alphabet ('-', '_') may replace '+' and '/'.
private val LENIENT_BASE64 = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

/** Hex or base64 text as bytes, ignoring whitespace; null if it is neither. */
private fun decodeText(text: String): ByteArray? {
    val compact = text.filterNot { it.isWhitespace() }
    return try {
        if (compact.isNotEmpty() && compact.length % 2 == 0 && compact.all { it in HEX_DIGITS }) {
            ByteVector(compact).toByteArray()
        } else {
            LENIENT_BASE64.decode(compact.replace('-', '+').replace('_', '/'))
        }
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** Binary as-is; text (base64 or hex) decoded. */
private fun ByteArray.asBinary(): ByteArray =
    if (!isPsbt() && isText()) decodeText(decodeToString()) ?: throw PsbtException.UnrecognizedSignedData() else this

private fun readPsbt(bytes: ByteArray): Psbt {
    val read = Psbt.read(bytes)
    return read.right ?: throw PsbtException.UnrecognizedSignedData(IllegalArgumentException("PSBT: ${read.left}"))
}

private fun readTransaction(bytes: ByteArray): Transaction =
    try {
        Transaction.read(bytes)
    } catch (e: Exception) {
        throw PsbtException.UnrecognizedSignedData(e)
    }

// BIP-174 magic: "psbt" followed by 0xff.
private val PSBT_MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())

private fun ByteArray.isPsbt(): Boolean =
    size >= PSBT_MAGIC.size && copyOfRange(0, PSBT_MAGIC.size).contentEquals(PSBT_MAGIC)

// A binary transaction starts with its version (0x01 or 0x02), which isn't
// printable; a text file is valid UTF-8 whose characters are text.
private fun ByteArray.isText(): Boolean {
    if (isEmpty() || any { it in 0x00..0x08 || it in 0x0e..0x1f }) return false
    val text = decodeToString()
    return '�' !in text
}

/**
 * Throws unless [other] is the same transaction apart from signatures: same
 * inputs in the same order with the same sequences, same outputs, same
 * version and lock time.
 */
private fun Transaction.requireSameAs(other: Transaction) {
    val differing = when {
        version != other.version -> Part.VERSION
        lockTime != other.lockTime -> Part.LOCK_TIME
        txIn.map { it.outPoint to it.sequence } != other.txIn.map { it.outPoint to it.sequence } -> Part.INPUTS
        txOut != other.txOut -> Part.OUTPUTS
        else -> return
    }
    throw PsbtException.SignedTransactionMismatch(differing)
}
