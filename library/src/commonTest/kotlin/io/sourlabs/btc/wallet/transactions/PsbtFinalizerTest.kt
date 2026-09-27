package io.sourlabs.btc.wallet.transactions

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.SigVersion
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.psbt.Input
import fr.acinq.bitcoin.psbt.Psbt
import io.sourlabs.btc.wallet.api.PsbtException
import io.sourlabs.btc.wallet.models.Purpose
import io.sourlabs.btc.wallet.models.TransactionStatus
import io.sourlabs.btc.wallet.models.TransactionType
import io.sourlabs.btc.wallet.models.UnspentOutput
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [PsbtFinalizer] must turn every form a hardware wallet returns into the
 * transaction the wallet built, refuse anything else before a broadcast is
 * possible, and record a broadcast spend the way a signed send is recorded.
 */
class PsbtFinalizerTest {

    // Another BIP-173 test vector (P2WSH), for an attacker's output.
    private val attackerScript =
        ByteVector(Script.write(Script.pay2wsh(ByteVector32.fromValidHex("1863143c14c5166804bd19203356da136c985678cd4d27a1b8c6329604903262"))))

    /** A wallet funded by two parents, and an unsigned PSBT that spends both with change. */
    private suspend fun builtPsbt(purpose: Purpose = Purpose.BIP84): Pair<PsbtTestWallet, PsbtDraft> {
        val wallet = PsbtTestWallet.create(descriptorConfig(purpose))
        wallet.fund(wallet.externalKey(0) to 60_000)
        wallet.fund(wallet.externalKey(1) to 60_000)
        return wallet to wallet.creator.build(EXTERNAL_DESTINATION, amount = 100_000, feeRate = 2, subtractFeeFromAmount = false)
    }

    private fun Psbt.bytes(): ByteArray = Psbt.write(this).toByteArray()

    private fun Transaction.hex(): String = ByteVector(Transaction.write(this)).toHex()

    private fun signedRaw(draft: PsbtDraft): Transaction = decode(draft).signAll().finalizeAll().extract().right!!

    /** [tx] with input [index] re-signed (P2WPKH) by [key] under [sighashType]. */
    private fun resigned(tx: Transaction, draft: PsbtDraft, index: Int, key: PrivateKey, sighashType: Int): Transaction {
        val publicKey = PublicKey(tx.txIn[index].witness.stack[1])
        val amount = decode(draft).inputs[index].witnessUtxo!!.amount
        val signature = Transaction.signInput(
            tx, index, Script.pay2pkh(publicKey), sighashType, amount, SigVersion.SIGVERSION_WITNESS_V0, key
        )
        return tx.copy(txIn = tx.txIn.mapIndexed { i, txIn ->
            if (i == index) txIn.copy(witness = ScriptWitness(listOf(ByteVector(signature), publicKey.value))) else txIn
        })
    }

    private fun Psbt.withTx(tx: Transaction): Psbt = copy(global = global.copy(tx = tx))

    /** Refused with [T]; nothing reaches the explorer. */
    private suspend inline fun <reified T : PsbtException> PsbtTestWallet.assertRefused(draft: PsbtDraft, signed: ByteArray) {
        assertFailsWith<T> { finalizer.finalize(draft.base64, signed) }
        assertTrue(broadcasts.isEmpty())
    }

    @Test
    fun unfinalizedSignedPsbtFinalizesForEveryScriptType() = runTest {
        for (purpose in Purpose.entries) {
            val (wallet, draft) = builtPsbt(purpose)
            val unsigned = decode(draft)

            val signed = wallet.finalizer.finalize(draft.base64, unsigned.signAll().bytes())

            // finalize() has run the consensus script checks on every input.
            assertEquals(unsigned.global.tx.txOut, signed.transaction.txOut, "$purpose")
            assertEquals(draft.fee, signed.fee, "$purpose")
        }
    }

    @Test
    fun finalizedPsbtAndRawTransactionGiveTheSameTransactionForEveryScriptType() = runTest {
        for (purpose in Purpose.entries) {
            val (wallet, draft) = builtPsbt(purpose)
            val finalized = decode(draft).signAll().finalizeAll()
            val raw = finalized.extract().right!!
            val base64 = Base64.encode(finalized.bytes())

            val results = listOf(
                wallet.finalizer.finalize(draft.base64, finalized.bytes()),
                wallet.finalizer.finalize(draft.base64, base64),
                wallet.finalizer.finalize(draft.base64, base64.encodeToByteArray()),
                wallet.finalizer.finalize(draft.base64, base64.trimEnd('=').replace('+', '-').replace('/', '_')),
                wallet.finalizer.finalize(draft.base64, ByteVector(finalized.bytes()).toHex()),
                wallet.finalizer.finalize(draft.base64, Transaction.write(raw)),
                wallet.finalizer.finalize(draft.base64, "  ${raw.hex()}\n"),
                wallet.finalizer.finalize(draft.base64, "${raw.hex()}\n".encodeToByteArray()),
            )

            results.forEach { assertEquals(raw, it.transaction, "$purpose") }
        }
    }

    @Test
    fun unsignedPsbtIsReadAsTextToo() = runTest {
        val (wallet, draft) = builtPsbt()
        val signed = decode(draft).signAll().bytes()

        assertEquals(draft.fee, wallet.finalizer.finalize("${draft.base64}\n", signed).fee)
        assertFailsWith<PsbtException.UnknownUnsignedPsbt> { wallet.finalizer.finalize("not a psbt", signed) }
    }

    @Test
    fun unsignedPsbtThatSpendsOutputsTheWalletDoesNotHoldIsRefused() = runTest {
        val (wallet, draft) = builtPsbt()
        val unsigned = decode(draft)
        val signed = unsigned.signAll().bytes()
        // A template claiming 1 BTC more for input 0 (without its parent, which
        // ACINQ would check the amount against).
        val inflated = unsigned.copy(inputs = unsigned.inputs.mapIndexed { i, input ->
            if (i == 0 && input is Input.WitnessInput.PartiallySignedWitnessInput) {
                input.copy(txOut = input.txOut.copy(amount = input.txOut.amount + Satoshi(100_000_000)), nonWitnessUtxo = null)
            } else {
                input
            }
        })

        assertFailsWith<PsbtException.UnknownUnsignedPsbt> {
            wallet.finalizer.finalize(Base64.encode(inflated.bytes()), signed)
        }
        // A UTXO the wallet no longer holds.
        wallet.storage.unspentOutputStorage.deleteUtxo(UnspentOutput.idOf(unsigned.global.tx.txIn[1].outPoint))
        assertFailsWith<PsbtException.UnknownUnsignedPsbt> { wallet.finalizer.finalize(draft.base64, signed) }
    }

    @Test
    fun signerCannotChangeTheAmountsThatAreChecked() = runTest {
        val (wallet, draft) = builtPsbt()
        val signed = decode(draft).signAll()
        // The signer's copy claims input 0 is worth 1 BTC more. It drops the parent
        // transaction too, as some signers do: ACINQ won't even read a PSBT whose
        // amount contradicts the parent it carries.
        val inflated = signed.copy(inputs = signed.inputs.mapIndexed { i, input ->
            if (i == 0 && input is Input.WitnessInput.PartiallySignedWitnessInput) {
                input.copy(txOut = input.txOut.copy(amount = input.txOut.amount + Satoshi(100_000_000)), nonWitnessUtxo = null)
            } else {
                input
            }
        })

        assertEquals(draft.fee, wallet.finalizer.finalize(draft.base64, inflated.bytes()).fee)
    }

    @Test
    fun broadcastSendsTheTransactionAndRecordsTheSpend() = runTest {
        val (wallet, draft) = builtPsbt()
        val signed = wallet.finalizer.finalize(draft.base64, decode(draft).signAll().bytes())

        val result = wallet.finalizer.broadcast(signed)

        assertEquals(signed.txId, result.getOrThrow())
        assertEquals(listOf(signed.transaction.hex()), wallet.broadcasts)
        assertTrue(wallet.storage.unspentOutputStorage.getAllUtxos().isEmpty(), "spent UTXOs removed")
        val recorded = wallet.storage.transactionStorage.getTransaction(signed.txId)!!
        assertEquals(TransactionStatus.PENDING, recorded.status)
        assertEquals(TransactionType.OUTGOING, recorded.type)
        assertEquals(signed.fee, recorded.fee)
        assertEquals(-(100_000 + signed.fee), recorded.amount)
        for (key in listOf(wallet.externalKey(0), wallet.externalKey(1), wallet.firstChangeKey())) {
            assertTrue(wallet.publicKeyManager.findByPath(key.path)!!.isUsed, "${key.path} used")
        }
    }

    @Test
    fun broadcastKeepsTheRecordOfAPollThatSawItFirst() = runTest {
        val (wallet, draft) = builtPsbt()
        val signed = wallet.finalizer.finalize(draft.base64, decode(draft).signAll().bytes())
        // A poll found it in the mempool between the broadcast and the record.
        val polled = incoming(signed.transaction).copy(
            type = TransactionType.OUTGOING,
            status = TransactionStatus.PENDING,
            blockHeight = null,
            amount = -(100_000 + signed.fee),
        )
        wallet.storage.transactionStorage.saveTransaction(polled)

        wallet.finalizer.broadcast(signed).getOrThrow()

        assertEquals(polled, wallet.storage.transactionStorage.getTransaction(signed.txId))
    }

    @Test
    fun recordFailureAfterBroadcastStillReportsTheBroadcast() = runTest {
        val (wallet, draft) = builtPsbt()
        val signed = wallet.finalizer.finalize(draft.base64, decode(draft).signAll().bytes())
        wallet.recordFailure = IllegalStateException("disk full")

        assertEquals(signed.txId, wallet.finalizer.broadcast(signed).getOrThrow())
        assertEquals(1, wallet.broadcasts.size)
    }

    @Test
    fun paymentToTheWalletsOwnChangeAddressKeepsTheRealChangeKey() = runTest {
        val wallet = PsbtTestWallet.create(descriptorConfig(Purpose.BIP84))
        wallet.fund(wallet.externalKey(0) to 100_000)
        // Change goes to internal key 1; the payment to internal key 0, which
        // comes first both among the outputs and among the wallet's keys.
        val paidKey = wallet.firstChangeKey()
        wallet.publicKeyManager.markAsUsed(paidKey.path)
        val changeKey = wallet.publicKeyManager.getInternalPublicKeys().single { it.index == 1 }
        val draft = wallet.creator.build(wallet.converter.toAddress(paidKey), 30_000, 2, false)
        val signed = wallet.finalizer.finalize(draft.base64, decode(draft).signAll().bytes())

        wallet.finalizer.broadcast(signed).getOrThrow()

        assertTrue(wallet.publicKeyManager.findByPath(changeKey.path)!!.isUsed, "real change key used")
    }

    @Test
    fun failedBroadcastRecordsNothing() = runTest {
        val (wallet, draft) = builtPsbt()
        val signed = wallet.finalizer.finalize(draft.base64, decode(draft).signAll().bytes())
        wallet.broadcastFailure = IllegalStateException("rejected")

        assertTrue(wallet.finalizer.broadcast(signed).isFailure)

        assertEquals(2, wallet.storage.unspentOutputStorage.getAllUtxos().size)
        assertTrue(wallet.storage.transactionStorage.getTransactions().isEmpty())
        assertTrue(wallet.publicKeyManager.getInternalPublicKeys().none { it.isUsed })
    }

    @Test
    fun psbtWithAnotherOutputIsRefused() = runTest {
        val (wallet, draft) = builtPsbt()
        val signed = decode(draft).signAll()
        val tx = signed.global.tx
        val redirected = tx.copy(txOut = tx.txOut.mapIndexed { i, out -> if (i == 0) out.copy(publicKeyScript = attackerScript) else out })
        val raised = tx.copy(txOut = tx.txOut.mapIndexed { i, out -> if (i == 0) out.copy(amount = out.amount + Satoshi(1_000)) else out })

        val e = assertFailsWith<PsbtException.SignedTransactionMismatch> {
            wallet.finalizer.finalize(draft.base64, signed.withTx(redirected).bytes())
        }
        assertEquals("outputs", e.reason)
        wallet.assertRefused<PsbtException.SignedTransactionMismatch>(draft, signed.withTx(raised).bytes())
    }

    @Test
    fun rawTransactionWithOtherInputsOrFeeIsRefused() = runTest {
        val (wallet, draft) = builtPsbt()
        val raw = signedRaw(draft)
        val extraInput = TxIn(OutPoint(TxId(ByteVector32.fromValidHex("ff".repeat(32))), 0), 0xFFFFFFFDL)
        val change = raw.txOut.indexOfFirst { !it.publicKeyScript.contentEquals(wallet.converter.addressToScriptPubKey(EXTERNAL_DESTINATION)!!) }
        val tampered = listOf(
            raw.copy(txIn = raw.txIn + extraInput),
            raw.copy(txIn = raw.txIn.drop(1)),
            // Less change, so a higher fee.
            raw.copy(txOut = raw.txOut.mapIndexed { i, out -> if (i == change) out.copy(amount = out.amount - Satoshi(1_000)) else out }),
        )

        for (tx in tampered) {
            wallet.assertRefused<PsbtException.SignedTransactionMismatch>(draft, Transaction.write(tx))
        }
    }

    @Test
    fun missingSignatureIsRefused() = runTest {
        val (wallet, draft) = builtPsbt()

        wallet.assertRefused<PsbtException.SignatureInvalid>(draft, decode(draft).bytes())
    }

    @Test
    fun signatureFromAnotherKeyIsRefused() = runTest {
        val (wallet, draft) = builtPsbt()
        val otherKey = DeterministicWallet.derivePrivateKey(TEST_MASTER, "m/84'/0'/0'/0/7").privateKey

        val tampered = resigned(signedRaw(draft), draft, index = 0, key = otherKey, sighashType = SigHash.SIGHASH_ALL)

        wallet.assertRefused<PsbtException.SignatureInvalid>(draft, Transaction.write(tampered))
    }

    @Test
    fun signatureOverLessThanTheWholeTransactionIsRefusedForEveryScriptType() = runTest {
        // Valid signatures that leave the outputs, or the other inputs, open to change.
        val weakSighashes = listOf(SigHash.SIGHASH_NONE, SigHash.SIGHASH_ALL or SigHash.SIGHASH_ANYONECANPAY)
        for (purpose in Purpose.entries) {
            for (sighashType in weakSighashes) {
                val (wallet, draft) = builtPsbt(purpose)
                val weak = decode(draft).signAll(sighashType).finalizeAll()

                wallet.assertRefused<PsbtException.SignatureInvalid>(draft, weak.bytes())
                wallet.assertRefused<PsbtException.SignatureInvalid>(draft, Transaction.write(weak.extract().right!!))
            }
        }
    }

    @Test
    fun dataThatIsNeitherPsbtNorTransactionIsRefused() = runTest {
        val (wallet, draft) = builtPsbt()

        wallet.assertRefused<PsbtException.UnrecognizedSignedData>(draft, byteArrayOf(1, 2, 3))
        assertFailsWith<PsbtException.UnrecognizedSignedData> { wallet.finalizer.finalize(draft.base64, "not signed data!") }
    }
}
