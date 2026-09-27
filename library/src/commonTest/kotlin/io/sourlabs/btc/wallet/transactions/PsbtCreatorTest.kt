package io.sourlabs.btc.wallet.transactions

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.psbt.Input
import fr.acinq.bitcoin.psbt.KeyPathWithMaster
import fr.acinq.bitcoin.psbt.Psbt
import io.sourlabs.btc.wallet.api.BitcoinKit
import io.sourlabs.btc.wallet.api.PsbtException
import io.sourlabs.btc.wallet.core.WalletConfig
import io.sourlabs.btc.wallet.descriptors.DescriptorChecksum
import io.sourlabs.btc.wallet.keys.HDWalletManager
import io.sourlabs.btc.wallet.models.Purpose
import io.sourlabs.btc.wallet.models.WalletTransaction
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A PSBT from [PsbtCreator] must carry what a hardware wallet checks before it
 * signs, and must leave the wallet untouched. The signing tests act as the
 * hardware wallet: they find each key through the PSBT's own derivation
 * fields, derive it from the master key of the same test mnemonic, then sign.
 */
class PsbtCreatorTest {

    private fun derivation(path: String) = KeyPathWithMaster(TEST_MASTER_FINGERPRINT, KeyPath(path))

    @Test
    fun nativeSegwitInputsCarryParentTransactionAndDerivation() = runTest {
        val f = PsbtTestWallet.create(descriptorConfig(Purpose.BIP84))
        val utxos = f.fund(f.externalKey(0) to 60_000, f.externalKey(1) to 60_000)

        val draft = f.creator.build(EXTERNAL_DESTINATION, amount = 100_000, feeRate = 2, subtractFeeFromAmount = false)
        val psbt = decode(draft)

        assertEquals(2, psbt.inputs.size)
        psbt.inputs.forEachIndexed { i, input ->
            val txIn = psbt.global.tx.txIn[i]
            val utxo = utxos.single { it.toOutPoint() == txIn.outPoint }
            val key = f.publicKeyManager.findByPath(utxo.publicKeyPath)!!
            val witnessInput = assertIs<Input.WitnessInput.PartiallySignedWitnessInput>(input)
            assertEquals(utxo.toTxOut(), witnessInput.txOut, "WITNESS_UTXO")
            assertEquals(f.parents.getValue(utxo.toOutPoint().txid.toString()), witnessInput.nonWitnessUtxo, "NON_WITNESS_UTXO")
            assertEquals(mapOf(key.publicKey to derivation("m/84'/0'/0'/0/${key.index}")), witnessInput.derivationPaths)
            assertEquals(0xFFFFFFFDL, txIn.sequence, "RBF sequence")
        }
        assertEquals(1, f.fetchedTxIds.size, "one request for the shared parent")
    }

    @Test
    fun changeOutputIsMarkedAsChangeForEveryScriptType() = runTest {
        for (purpose in Purpose.entries) {
            val f = PsbtTestWallet.create(descriptorConfig(purpose))
            f.fund(f.externalKey(0) to 100_000)
            val changeKey = f.firstChangeKey()

            val draft = f.creator.build(EXTERNAL_DESTINATION, amount = 30_000, feeRate = 2, subtractFeeFromAmount = false)
            val psbt = decode(draft)

            assertEquals(2, psbt.outputs.size, "$purpose")
            val payment = psbt.global.tx.txOut.indexOfFirst {
                it.publicKeyScript.contentEquals(f.converter.addressToScriptPubKey(EXTERNAL_DESTINATION)!!)
            }
            assertTrue(psbt.outputs[payment].derivationPaths.isEmpty(), "$purpose payment")
            assertTrue(psbt.outputs[payment].taprootDerivationPaths.isEmpty(), "$purpose payment")
            // Read back, an output's class depends on which scripts it carries,
            // so compare the fields, not the classes.
            val change = psbt.outputs[1 - payment]
            val changePath = KeyPath("m/${purpose.value}'/0'/0'/1/${changeKey.index}")
            if (purpose == Purpose.BIP86) {
                val internalKey = XonlyPublicKey(changeKey.publicKey)
                assertEquals(internalKey, change.taprootInternalKey, "$purpose")
                val taprootDerivation = change.taprootDerivationPaths.getValue(internalKey)
                assertEquals(TEST_MASTER_FINGERPRINT, taprootDerivation.masterKeyFingerprint, "$purpose")
                assertEquals(changePath, taprootDerivation.keyPath, "$purpose")
            } else {
                assertEquals(
                    mapOf(changeKey.publicKey to KeyPathWithMaster(TEST_MASTER_FINGERPRINT, changePath)),
                    change.derivationPaths,
                    "$purpose",
                )
            }
            val redeemScript = if (purpose == Purpose.BIP49) Script.pay2wpkh(changeKey.publicKey) else null
            assertEquals(redeemScript, change.redeemScript, "$purpose redeem script")
            assertEquals(30_000, draft.amount, "$purpose")
            assertEquals(Satoshi(draft.fee), psbt.computeFees(), "$purpose")
        }
    }

    @Test
    fun derivationIsSerializedAsBip174Requires() = runTest {
        val f = PsbtTestWallet.create(descriptorConfig(Purpose.BIP84))
        val key = f.externalKey(0)
        f.fund(key to 100_000)

        val psbtHex = ByteVector(Base64.decode(f.creator.build(EXTERNAL_DESTINATION, 30_000, 2, false).base64)).toHex()

        // PSBT_IN_BIP32_DERIVATION: key 0x06 || pubkey, value = fingerprint bytes
        // as in the origin, then each path step as a 32-bit little-endian integer.
        val entry = "22" + "06" + key.publicKey.value.toHex() +
            "18" + "73c5da0a" + "54000080" + "00000080" + "00000080" + "00000000" + "00000000"
        assertContains(psbtHex, entry)
    }

    @Test
    fun withoutChangeTheOnlyOutputIsThePayment() = runTest {
        val f = PsbtTestWallet.create(descriptorConfig(Purpose.BIP84))
        f.fund(f.externalKey(0) to 50_000)

        val draft = f.creator.build(EXTERNAL_DESTINATION, amount = 50_000, feeRate = 2, subtractFeeFromAmount = true)
        val psbt = decode(draft)

        assertEquals(1, psbt.outputs.size)
        assertTrue(psbt.outputs.single().derivationPaths.isEmpty())
        assertEquals(50_000, draft.amount + draft.fee)
        assertEquals(Satoshi(draft.fee), psbt.computeFees())
    }

    @Test
    fun taprootInputsCarryInternalKeyAndNeedNoParentTransaction() = runTest {
        val f = PsbtTestWallet.create(descriptorConfig(Purpose.BIP86))
        val key = f.externalKey(0)
        f.fund(key to 100_000)

        val psbt = decode(f.creator.build(EXTERNAL_DESTINATION, amount = 30_000, feeRate = 2, subtractFeeFromAmount = false))

        val input = assertIs<Input.WitnessInput.PartiallySignedWitnessInput>(psbt.inputs.single())
        val internalKey = XonlyPublicKey(key.publicKey)
        assertEquals(internalKey, input.taprootInternalKey)
        val inputDerivation = input.taprootDerivationPaths.getValue(internalKey)
        assertEquals(TEST_MASTER_FINGERPRINT, inputDerivation.masterKeyFingerprint)
        assertEquals(KeyPath("m/86'/0'/0'/0/0"), inputDerivation.keyPath)
        assertNull(input.nonWitnessUtxo)
        assertTrue(f.fetchedTxIds.isEmpty(), "taproot inputs need no parent fetch")
    }

    @Test
    fun parentsSavedBySyncAreNotFetched() = runTest {
        val f = PsbtTestWallet.create(descriptorConfig(Purpose.BIP84))
        val utxo = f.fund(f.externalKey(0) to 100_000, stored = true).single()

        val psbt = decode(f.creator.build(EXTERNAL_DESTINATION, amount = 30_000, feeRate = 2, subtractFeeFromAmount = false))

        assertTrue(f.fetchedTxIds.isEmpty())
        assertEquals(f.parents.getValue(utxo.toOutPoint().txid.toString()), psbt.inputs.single().nonWitnessUtxo)
    }

    @Test
    fun storedParentThatIsNotTheRightTransactionIsFetched() = runTest {
        val f = PsbtTestWallet.create(descriptorConfig(Purpose.BIP84))
        val utxo = f.fund(f.externalKey(0) to 100_000).single()
        val parentTxId = utxo.toOutPoint().txid.toString()
        // Saved under the parent's txid, but rebuilt wrong (another lock time).
        val parent = f.parents.getValue(parentTxId)
        f.storage.transactionStorage.saveTransaction(incoming(parent.copy(lockTime = 1)).copy(txId = parentTxId))

        val psbt = decode(f.creator.build(EXTERNAL_DESTINATION, amount = 30_000, feeRate = 2, subtractFeeFromAmount = false))

        assertEquals(listOf(parentTxId), f.fetchedTxIds)
        assertEquals(parent, psbt.inputs.single().nonWitnessUtxo)
    }

    @Test
    fun kitBuildsFromStoredParentsWithoutSyncing() = runTest {
        val config = descriptorConfig(Purpose.BIP84)
        val f = PsbtTestWallet.create(config)
        f.fund(f.externalKey(0) to 100_000, stored = true)

        val kit = BitcoinKit.builder(config).storage(f.storage).build()
        val psbt = decode(kit.buildPsbt(EXTERNAL_DESTINATION, amount = 30_000, feeRate = 2))

        assertNotNull(psbt.inputs.single().nonWitnessUtxo)
    }

    @Test
    fun kitThatIsNotRunningCannotFetchAMissingParent() = runTest {
        val config = descriptorConfig(Purpose.BIP84)
        val f = PsbtTestWallet.create(config)
        val utxo = f.fund(f.externalKey(0) to 100_000).single()

        val kit = BitcoinKit.builder(config).storage(f.storage).build()
        val e = assertFailsWith<PsbtException.ParentTransactionUnavailable> {
            kit.buildPsbt(EXTERNAL_DESTINATION, amount = 30_000, feeRate = 2)
        }

        assertEquals(utxo.toOutPoint().txid.toString(), e.txId)
        assertIs<IllegalStateException>(e.cause)
    }

    @Test
    fun psbtSurvivesSerializationUnchanged() = runTest {
        val f = PsbtTestWallet.create(descriptorConfig(Purpose.BIP84))
        f.fund(f.externalKey(0) to 60_000, f.externalKey(1) to 60_000)

        val draft = f.creator.build(EXTERNAL_DESTINATION, amount = 100_000, feeRate = 2, subtractFeeFromAmount = false)

        assertContentEquals(Base64.decode(draft.base64), Psbt.write(decode(draft)).toByteArray())
    }

    @Test
    fun signedPsbtExtractsToAValidTransactionForEveryScriptType() = runTest {
        for (purpose in Purpose.entries) {
            val f = PsbtTestWallet.create(descriptorConfig(purpose))
            f.fund(f.externalKey(0) to 60_000)
            f.fund(f.externalKey(1) to 60_000)

            val psbt = decode(f.creator.build(EXTERNAL_DESTINATION, amount = 100_000, feeRate = 2, subtractFeeFromAmount = false))
            val signed = signAndExtract(psbt)

            // extract() has run the consensus script checks on every input.
            assertEquals(psbt.global.tx.txOut, signed.txOut, "$purpose outputs")
            assertEquals(psbt.global.tx.txIn.map { it.outPoint }, signed.txIn.map { it.outPoint }, "$purpose inputs")
        }
    }

    @Test
    fun buildingAPsbtLeavesWalletStateUntouched() = runTest {
        val f = PsbtTestWallet.create(descriptorConfig(Purpose.BIP84))
        val utxos = f.fund(f.externalKey(0) to 100_000)

        f.creator.build(EXTERNAL_DESTINATION, amount = 30_000, feeRate = 2, subtractFeeFromAmount = false)

        assertEquals(utxos, f.storage.unspentOutputStorage.getAllUtxos())
        assertTrue(f.publicKeyManager.getExternalPublicKeys().none { it.isUsed })
        assertTrue(f.publicKeyManager.getInternalPublicKeys().none { it.isUsed })
        assertTrue(f.storage.transactionStorage.getTransactions().isEmpty())
    }

    @Test
    fun walletWithoutKeyOriginIsRefused() = runTest {
        val bip84 = WalletConfig.FromMnemonic(TEST_MNEMONIC, purpose = Purpose.BIP84)
        val xpub = HDWalletManager.fromConfig(bip84).accountXpub.encode(testnet = false)
        val bareDescriptor = "wpkh($xpub/0/*)"
        val originLessConfigs = listOf(
            WalletConfig.WatchOnly(extendedPublicKey = xpub, purpose = Purpose.BIP84),
            WalletConfig.WatchOnlyDescriptor("$bareDescriptor#${DescriptorChecksum.compute(bareDescriptor)}"),
        )
        for (config in originLessConfigs) {
            val kit = BitcoinKit.builder(config).build()
            assertFailsWith<PsbtException.MissingKeyOrigin>("$config") {
                kit.buildPsbt(EXTERNAL_DESTINATION, amount = 30_000, feeRate = 2)
            }
        }
    }

    @Test
    fun keyOriginThatDoesNotDescribeTheAccountKeyIsRefused() = runTest {
        val bip84 = WalletConfig.FromMnemonic(TEST_MNEMONIC, purpose = Purpose.BIP84)
        val xpub = HDWalletManager.fromConfig(bip84).accountXpub.encode(testnet = false)
        // No path, one step too many, and the wrong account for this key.
        val badOrigins = listOf("[73c5da0a]", "[73c5da0a/84h/0h/0h/0]", "[73c5da0a/84h/0h/1h]")
        for (origin in badOrigins) {
            val body = "wpkh($origin$xpub/<0;1>/*)"
            val kit = BitcoinKit.builder(WalletConfig.WatchOnlyDescriptor("$body#${DescriptorChecksum.compute(body)}")).build()
            assertFailsWith<PsbtException.KeyOriginMismatch>(origin) {
                kit.buildPsbt(EXTERNAL_DESTINATION, amount = 30_000, feeRate = 2)
            }
        }
    }

    @Test
    fun multisigWalletIsRefused() = runTest {
        // A 2-of-3 Bitkey export with no funds (same as BitcoinKitMultisigBuilderTest).
        val body = "wsh(sortedmulti(2," +
            "[151c0436/84'/0'/0']xpub6CKrVEyoK68fJ5WnniiFoiQCsXa32rnM7rHXoiFZji1g9nJdgWtydoXfWQeGSt4LVjwptLQTnZfdKV2b37Ux5asMBfDXt2KBzFJKU2i5vfr/0/*," +
            "[d0ec9d96/84'/0'/0']xpub6D6DBgdzpTbAL4Ln4ufFiYdiRKRKg1cMgDtzYGHSmyuHxvUBqkfjQsc7rDc1y66i1v5KUKLSNU6AbG69kEQDNbyGY5KZ6tZL9dFg8to9Ekc/0/*," +
            "[c6f75db6/84'/0'/0']xpub6Cv5iQBhcq7buXp5VbrAzzH3tPNTDCvzLFkFVhrc4Ybet8EHH6oSs16UuD2FMmAHQn7TiuCVtBuPNz7rCjsg9pdEKBo7aCUAiMGFbt9W387/0/*" +
            "))"
        val kit = BitcoinKit.builder(WalletConfig.WatchOnlyDescriptor("$body#${DescriptorChecksum.compute(body)}")).build()

        assertFailsWith<PsbtException.MultisigNotSupported> {
            kit.buildPsbt(EXTERNAL_DESTINATION, amount = 30_000, feeRate = 2)
        }
    }

    @Test
    fun parentFetchFailureIsTyped() = runTest {
        val f = PsbtTestWallet.create(descriptorConfig(Purpose.BIP84))
        val utxo = f.fund(f.externalKey(0) to 100_000).single()
        val parentTxId = utxo.toOutPoint().txid.toString()
        f.parents.remove(parentTxId)

        val e = assertFailsWith<PsbtException.ParentTransactionUnavailable> {
            f.creator.build(EXTERNAL_DESTINATION, amount = 30_000, feeRate = 2, subtractFeeFromAmount = false)
        }
        assertEquals(parentTxId, e.txId)
        assertNotNull(e.cause)
    }

    @Test
    fun parentWithAnotherTxidIsRefused() = runTest {
        val f = PsbtTestWallet.create(descriptorConfig(Purpose.BIP84))
        val utxo = f.fund(f.externalKey(0) to 100_000).single()
        val parentTxId = utxo.toOutPoint().txid.toString()
        // The explorer answers with a transaction whose amounts differ.
        val parent = f.parents.getValue(parentTxId)
        f.parents[parentTxId] = parent.copy(txOut = parent.txOut.map { it.copy(amount = it.amount + Satoshi(1)) })

        val e = assertFailsWith<PsbtException.ParentTransactionMismatch> {
            f.creator.build(EXTERNAL_DESTINATION, amount = 30_000, feeRate = 2, subtractFeeFromAmount = false)
        }
        assertEquals(parentTxId, e.txId)
    }

    @Test
    fun utxoThatDisagreesWithItsParentIsRefused() = runTest {
        val f = PsbtTestWallet.create(descriptorConfig(Purpose.BIP84))
        val utxo = f.fund(f.externalKey(0) to 100_000).single()
        // Sync recorded a smaller amount than the authentic parent pays: fee and
        // change computed from it would be wrong.
        f.storage.unspentOutputStorage.deleteUtxos(listOf(utxo.id))
        f.storage.unspentOutputStorage.saveUtxo(utxo.copy(value = 60_000))

        val e = assertFailsWith<PsbtException.ParentTransactionMismatch> {
            f.creator.build(EXTERNAL_DESTINATION, amount = 30_000, feeRate = 2, subtractFeeFromAmount = false)
        }
        assertEquals(utxo.toOutPoint().txid.toString(), e.txId)
    }

    @Test
    fun insufficientFundsIsReportedAsForASignedSend() = runTest {
        val f = PsbtTestWallet.create(descriptorConfig(Purpose.BIP84))
        f.fund(f.externalKey(0) to 10_000)

        assertFailsWith<InsufficientFundsException> {
            f.creator.build(EXTERNAL_DESTINATION, amount = 100_000, feeRate = 2, subtractFeeFromAmount = false)
        }
    }

    /** Sign and finalize as a hardware wallet would; [Psbt.extract] checks the consensus scripts. */
    private fun signAndExtract(psbt: Psbt): Transaction {
        val extracted = psbt.signAll().finalizeAll().extract()
        return extracted.right ?: fail("extract failed: ${extracted.left}")
    }
}
