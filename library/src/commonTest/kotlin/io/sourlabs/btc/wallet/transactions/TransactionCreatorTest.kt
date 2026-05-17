package io.sourlabs.btc.wallet.transactions

import fr.acinq.bitcoin.ByteVector32
import io.sourlabs.btc.wallet.keys.AddressConverter
import io.sourlabs.btc.wallet.keys.HDWalletManager
import io.sourlabs.btc.wallet.keys.PublicKeyManager
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import io.sourlabs.btc.wallet.models.TransactionStatus
import io.sourlabs.btc.wallet.models.TransactionType
import io.sourlabs.btc.wallet.models.UnspentOutput
import io.sourlabs.btc.wallet.models.WalletPublicKey
import io.sourlabs.btc.wallet.storage.InMemoryWalletStorage
import io.sourlabs.btc.wallet.utxo.UnspentOutputProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Covers PR-02 (audit finding C2): after create*() succeeds, the wallet must reflect the
 * spend locally — input/change keys marked used, spent UTXOs removed, PENDING tx saved —
 * so the next create*() in the same process doesn't reuse the same change address or
 * double-spend the same UTXOs.
 */
class TransactionCreatorTest {

    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    // A well-known external (non-wallet) destination from the BIP-173 test vectors.
    private val externalDestination = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"

    private fun newFixture(): Fixture {
        val hd = HDWalletManager.fromMnemonic(testMnemonic, "", Purpose.BIP84, Network.MAINNET)
        val converter = AddressConverter(Network.MAINNET)
        val storage = InMemoryWalletStorage()
        val pkm = PublicKeyManager(hd, storage.publicKeyStorage, gapLimit = 20)
        val provider = UnspentOutputProvider(storage.unspentOutputStorage, confirmationsThreshold = 1)
        val creator = TransactionCreator(
            hdWalletManager = hd,
            publicKeyManager = pkm,
            utxoProvider = provider,
            addressConverter = converter,
            transactionStorage = storage.transactionStorage,
            unspentOutputStorage = storage.unspentOutputStorage,
        )
        return Fixture(hd, converter, storage, pkm, creator)
    }

    private data class Fixture(
        val hd: HDWalletManager,
        val converter: AddressConverter,
        val storage: InMemoryWalletStorage,
        val publicKeyManager: PublicKeyManager,
        val creator: TransactionCreator,
    )

    private fun utxoBoundTo(
        key: WalletPublicKey,
        converter: AddressConverter,
        txidHexSeed: Int,
        value: Long,
    ): UnspentOutput {
        val txidHex = txidHexSeed.toString(16).padStart(64, '0')
        return UnspentOutput(
            transactionHash = ByteVector32.fromValidHex(txidHex),
            outputIndex = 0,
            value = value,
            scriptPubKey = converter.createScriptPubKey(key.publicKey, key.scriptType),
            scriptType = key.scriptType,
            confirmations = 6,
            publicKeyPath = key.path,
            blockHeight = 100,
            isSpendable = true,
        )
    }

    @Test
    fun createMarksInputAndChangeKeysUsedAndRemovesSpentUtxos() = runTest {
        val f = newFixture()
        f.publicKeyManager.initialize()
        val externalKeys = f.publicKeyManager.getExternalPublicKeys().sortedBy { it.index }
        val seedUtxo = utxoBoundTo(externalKeys[0], f.converter, txidHexSeed = 1, value = 100_000)
        f.storage.unspentOutputStorage.saveUtxo(seedUtxo)

        val tx = f.creator.create(externalDestination, amount = 30_000, feeRate = 1)

        // The selected UTXO is gone from local storage.
        val remaining = f.storage.unspentOutputStorage.getAllUtxos()
        assertEquals(0, remaining.size, "spent UTXO should be deleted")

        // The input key is marked used.
        val refreshedInputKey = f.publicKeyManager.findByPath(externalKeys[0].path)!!
        assertTrue(refreshedInputKey.isUsed, "input key should be marked used")

        // Exactly one internal key is now used (the change key picked by the builder).
        val usedChangeKeys = f.publicKeyManager.getInternalPublicKeys().filter { it.isUsed }
        assertEquals(1, usedChangeKeys.size, "exactly one change key should be marked used")

        // A PENDING tx with the right txId, negative net amount, and known fee is saved.
        val saved = f.storage.transactionStorage.getTransaction(tx.txId)!!
        assertEquals(TransactionStatus.PENDING, saved.status)
        assertEquals(TransactionType.OUTGOING, saved.type)
        assertEquals(tx.fee, saved.fee)
        assertTrue(saved.amount < 0, "outgoing tx amount should be negative")
    }

    @Test
    fun secondCreateUsesDifferentChangeKeyAndDifferentUtxos() = runTest {
        val f = newFixture()
        f.publicKeyManager.initialize()
        val externalKeys = f.publicKeyManager.getExternalPublicKeys().sortedBy { it.index }

        // Two separately-keyed UTXOs of 100k each. After a 30k spend, exactly one
        // should be consumed; the second create() must pick the other.
        f.storage.unspentOutputStorage.saveUtxo(utxoBoundTo(externalKeys[0], f.converter, 1, 100_000))
        f.storage.unspentOutputStorage.saveUtxo(utxoBoundTo(externalKeys[1], f.converter, 2, 100_000))

        val tx1 = f.creator.create(externalDestination, amount = 30_000, feeRate = 1)
        val internalKeysAfterFirst = f.publicKeyManager.getInternalPublicKeys()
            .filter { it.isUsed }
            .map { it.path }
        val utxosAfterFirst = f.storage.unspentOutputStorage.getAllUtxos().map { it.id }

        val tx2 = f.creator.create(externalDestination, amount = 30_000, feeRate = 1)
        val internalKeysAfterSecond = f.publicKeyManager.getInternalPublicKeys()
            .filter { it.isUsed }
            .map { it.path }
        val utxosAfterSecond = f.storage.unspentOutputStorage.getAllUtxos().map { it.id }

        // Different change keys.
        assertEquals(1, internalKeysAfterFirst.size)
        assertEquals(2, internalKeysAfterSecond.size, "second send should mark a new change key used")
        assertNotEquals(internalKeysAfterFirst.first(), internalKeysAfterSecond.last())

        // Different UTXOs consumed.
        assertEquals(1, utxosAfterFirst.size, "one UTXO remaining after first send")
        assertEquals(0, utxosAfterSecond.size, "all UTXOs consumed after second send")

        // tx1 and tx2 are distinct.
        assertNotEquals(tx1.txId, tx2.txId)

        // Two PENDING entries in history.
        val pending = f.storage.transactionStorage.getTransactions()
            .filter { it.status == TransactionStatus.PENDING }
            .map { it.txId }
        assertEquals(2, pending.size)
        assertContains(pending, tx1.txId)
        assertContains(pending, tx2.txId)
    }

    @Test
    fun selfSendAmountIsNegativeFeeOnly() = runTest {
        // Self-send (destination is one of our own addresses, e.g. consolidation):
        // the wallet's net change is just -fee — the destination amount comes back
        // to us. Anchors against the bug where outputAmount only counted the change
        // output and ignored wallet-owned destination outputs.
        val f = newFixture()
        f.publicKeyManager.initialize()
        val externalKeys = f.publicKeyManager.getExternalPublicKeys().sortedBy { it.index }

        // Destination = our own external key #0.
        val ownDestinationAddress = f.converter.toP2WPKHAddress(externalKeys[0].publicKey)
        // Fund: 100k on external key #1.
        f.storage.unspentOutputStorage.saveUtxo(utxoBoundTo(externalKeys[1], f.converter, 1, 100_000))

        val tx = f.creator.create(ownDestinationAddress, amount = 30_000, feeRate = 1)
        val saved = f.storage.transactionStorage.getTransaction(tx.txId)!!

        assertEquals(
            -tx.fee, saved.amount,
            "self-send amount should be -fee only (destination returns to wallet)"
        )
    }

    @Test
    fun sweepRemovesAllSpendableUtxosAndPersistsPendingTx() = runTest {
        val f = newFixture()
        f.publicKeyManager.initialize()
        val externalKeys = f.publicKeyManager.getExternalPublicKeys().sortedBy { it.index }

        // Three small UTXOs across three keys.
        f.storage.unspentOutputStorage.saveUtxo(utxoBoundTo(externalKeys[0], f.converter, 1, 50_000))
        f.storage.unspentOutputStorage.saveUtxo(utxoBoundTo(externalKeys[1], f.converter, 2, 50_000))
        f.storage.unspentOutputStorage.saveUtxo(utxoBoundTo(externalKeys[2], f.converter, 3, 50_000))

        val tx = f.creator.createSweep(externalDestination, feeRate = 1)

        // Everything spent — sweep has no change.
        assertEquals(0, f.storage.unspentOutputStorage.getAllUtxos().size)
        // No internal key used (sweep has no change output).
        assertEquals(0, f.publicKeyManager.getInternalPublicKeys().count { it.isUsed })
        // All three external keys marked used.
        assertEquals(3, f.publicKeyManager.getExternalPublicKeys().count { it.isUsed })
        // PENDING tx persisted.
        val saved = f.storage.transactionStorage.getTransaction(tx.txId)!!
        assertEquals(TransactionStatus.PENDING, saved.status)
        assertEquals(TransactionType.OUTGOING, saved.type)
    }
}
