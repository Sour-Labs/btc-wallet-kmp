package io.sourlabs.btc.wallet.transactions

import co.touchlab.kermit.Logger
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.psbt.Input
import fr.acinq.bitcoin.psbt.Psbt
import io.sourlabs.btc.wallet.api.PsbtException
import io.sourlabs.btc.wallet.keys.AddressConverter
import io.sourlabs.btc.wallet.keys.PublicKeyManager
import io.sourlabs.btc.wallet.models.ScriptType
import io.sourlabs.btc.wallet.models.UnspentOutput
import io.sourlabs.btc.wallet.models.WalletPublicKey
import io.sourlabs.btc.wallet.storage.UnspentOutputStorage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

private val log = Logger.withTag("PsbtFinalizer")

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
     * Fee paid, from the wallet's records of the spent UTXOs.
     */
    val fee: Long,

    internal val spentUtxos: List<UnspentOutput>,
    internal val inputKeys: List<WalletPublicKey>,
    internal val changeKey: WalletPublicKey?
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
 * The unsigned PSBT comes back from the caller, so it is checked too: each of
 * its inputs must be a stored UTXO of this wallet. Amounts, scripts and keys
 * come from those records, never from the PSBTs.
 */
internal class PsbtFinalizer(
    private val unspentOutputStorage: UnspentOutputStorage,
    private val publicKeyManager: PublicKeyManager,
    private val addressConverter: AddressConverter,
    private val transactionCreator: TransactionCreator,
    private val broadcastRawTransaction: suspend (rawTxHex: String) -> Result<String>
) {
    /**
     * @param unsignedPsbtBase64 the PSBT the wallet built ([PsbtDraft.base64])
     * @param signed what the signer returned: a PSBT or a raw transaction, as
     *   binary or as text (base64 or hex), e.g. a BBQr payload or a file
     */
    suspend fun finalize(unsignedPsbtBase64: String, signed: ByteArray): SignedTransaction {
        val unsigned = decodeText(unsignedPsbtBase64)?.let { Psbt.read(it).right }
            ?: throw PsbtException.UnknownUnsignedPsbt("The unsigned PSBT does not read as a PSBT")
        val template = unsigned.global.tx
        val spentUtxos = template.txIn.mapIndexed { index, txIn ->
            val input = unsigned.inputs[index]
            val claimed = input.witnessUtxo ?: input.nonWitnessUtxo?.txOut?.getOrNull(txIn.outPoint.index.toInt())
            unspentOutputStorage.getUtxo(UnspentOutput.idOf(txIn.outPoint))?.takeIf { it.toTxOut() == claimed }
                ?: throw PsbtException.UnknownUnsignedPsbt("Input $index is not a UTXO of this wallet")
        }
        val inputKeys = publicKeyManager.keysFor(spentUtxos)

        val bytes = signed.asBinary()
        val candidate = if (bytes.isPsbt()) {
            val signedPsbt = readPsbt(bytes)
            template.requireSameAs(signedPsbt.global.tx)
            template.copy(txIn = template.txIn.mapIndexed { index, txIn ->
                txIn.finalizedFrom(signedPsbt.inputs[index], inputKeys[index], index)
            })
        } else {
            readTransaction(bytes).also { template.requireSameAs(it) }
        }
        verifySignatures(candidate, spentUtxos, inputKeys)

        return SignedTransaction(
            transaction = candidate,
            fee = spentUtxos.sumOf { it.value } - candidate.txOut.sumOf { it.amount.sat },
            spentUtxos = spentUtxos,
            inputKeys = inputKeys,
            changeKey = changeKeyOf(unsigned)
        )
    }

    /**
     * [finalize] for text: a PSBT in base64 or hex, or a raw transaction in hex.
     */
    suspend fun finalize(unsignedPsbtBase64: String, signed: String): SignedTransaction =
        finalize(unsignedPsbtBase64, signed.encodeToByteArray())

    /**
     * Broadcast [signed] and, once the explorer accepts it, record the spend as
     * a signed send does, so balance and history update at once.
     */
    suspend fun broadcast(signed: SignedTransaction): Result<String> {
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
            ScriptType.P2TR, ScriptType.P2SH, ScriptType.P2WSH -> error("${key.scriptType} input in a single-key wallet")
        }
    }

    private fun verifySignatures(tx: Transaction, spentUtxos: List<UnspentOutput>, inputKeys: List<WalletPublicKey>) {
        try {
            tx.correctlySpends(
                spentUtxos.associate { it.toOutPoint() to it.toTxOut() },
                ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS
            )
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

    /**
     * The key of the change output: the output the PSBT marks with a derivation
     * ([PsbtCreator] marks only change) that pays to one of the wallet's
     * internal keys. A payment to the wallet's own address carries no mark.
     */
    private suspend fun changeKeyOf(unsigned: Psbt): WalletPublicKey? {
        val markedScripts = unsigned.outputs.indices
            .filter { unsigned.outputs[it].derivationPaths.isNotEmpty() || unsigned.outputs[it].taprootDerivationPaths.isNotEmpty() }
            .map { unsigned.global.tx.txOut[it].publicKeyScript }
        if (markedScripts.isEmpty()) return null
        return publicKeyManager.getInternalPublicKeys()
            .firstOrNull { ByteVector(addressConverter.createScriptPubKey(it)) in markedScripts }
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

// A binary transaction starts with its version (0x01 or 0x02), which isn't printable.
private fun ByteArray.isText(): Boolean =
    isNotEmpty() && all { it in 0x20..0x7e || it == '\t'.code.toByte() || it == '\n'.code.toByte() || it == '\r'.code.toByte() }

/**
 * Throws unless [other] is the same transaction apart from signatures: same
 * inputs in the same order with the same sequences, same outputs, same
 * version and lock time.
 */
private fun Transaction.requireSameAs(other: Transaction) {
    val difference = when {
        version != other.version -> "version"
        lockTime != other.lockTime -> "lock time"
        txIn.map { it.outPoint to it.sequence } != other.txIn.map { it.outPoint to it.sequence } -> "inputs"
        txOut != other.txOut -> "outputs"
        else -> return
    }
    throw PsbtException.SignedTransactionMismatch(difference)
}
