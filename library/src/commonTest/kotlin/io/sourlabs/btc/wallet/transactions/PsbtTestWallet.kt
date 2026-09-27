package io.sourlabs.btc.wallet.transactions

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Input
import fr.acinq.bitcoin.psbt.KeyPathWithMaster
import fr.acinq.bitcoin.psbt.Psbt
import io.sourlabs.btc.wallet.core.WalletConfig
import io.sourlabs.btc.wallet.descriptors.WalletDescriptorExport
import io.sourlabs.btc.wallet.keys.AddressConverter
import io.sourlabs.btc.wallet.keys.HDWalletManager
import io.sourlabs.btc.wallet.keys.HdWalletKeySource
import io.sourlabs.btc.wallet.keys.PublicKeyManager
import io.sourlabs.btc.wallet.keys.SeedManager.toSeed
import io.sourlabs.btc.wallet.models.BlockInfo
import io.sourlabs.btc.wallet.models.Purpose
import io.sourlabs.btc.wallet.models.TransactionStatus
import io.sourlabs.btc.wallet.models.TransactionType
import io.sourlabs.btc.wallet.models.UnspentOutput
import io.sourlabs.btc.wallet.models.WalletPublicKey
import io.sourlabs.btc.wallet.models.WalletTransaction
import io.sourlabs.btc.wallet.storage.InMemoryWalletStorage
import io.sourlabs.btc.wallet.utxo.UnspentOutputProvider
import kotlin.io.encoding.Base64
import kotlin.test.assertEquals
import kotlin.test.fail

internal val TEST_MNEMONIC = listOf(
    "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
    "abandon", "abandon", "abandon", "abandon", "abandon", "about"
)

// Master key of the test mnemonic, and its well-known fingerprint 73c5da0a.
internal val TEST_MASTER = DeterministicWallet.generate(ByteVector(TEST_MNEMONIC.toSeed()))
internal const val TEST_MASTER_FINGERPRINT = 0x73c5da0aL

// A well-known external (non-wallet) destination from the BIP-173 test vectors.
internal const val EXTERNAL_DESTINATION = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"

/** The watch-only wallet a hardware wallet exports for [purpose]: a descriptor with key origin. */
internal fun descriptorConfig(purpose: Purpose): WalletConfig =
    WalletConfig.WatchOnlyDescriptor(
        WalletDescriptorExport.outputDescriptor(WalletConfig.FromMnemonic(TEST_MNEMONIC, purpose = purpose))
    )

/**
 * A watch-only wallet on in-memory storage for PSBT tests. Its fake explorer
 * serves [parents] and records every fetch and broadcast.
 */
internal class PsbtTestWallet private constructor(
    val storage: InMemoryWalletStorage,
    val publicKeyManager: PublicKeyManager,
    val converter: AddressConverter,
    val creator: PsbtCreator,
    /** What the fake explorer serves, by txid. */
    val parents: MutableMap<String, Transaction>,
    val fetchedTxIds: MutableList<String>,
    /** Raw transactions (hex) sent to the fake explorer. */
    val broadcasts: MutableList<String>,
    finalizerFor: (PsbtTestWallet) -> PsbtFinalizer,
) {
    /** When set, the fake explorer rejects broadcasts with it. */
    var broadcastFailure: Exception? = null

    val finalizer: PsbtFinalizer = finalizerFor(this)

    /**
     * Pay each of [payments] from one new parent transaction and save the
     * resulting UTXOs. Output 0 pays someone else, so a wrong output index shows.
     * The explorer serves the parent; with [stored], sync also saved it locally.
     */
    suspend fun fund(
        vararg payments: Pair<WalletPublicKey, Long>,
        stored: Boolean = false,
    ): List<UnspentOutput> {
        val decoy = TxOut(Satoshi(1_000), ByteVector(converter.addressToScriptPubKey(EXTERNAL_DESTINATION)!!))
        val parent = Transaction(
            version = 2,
            // A distinct previous outpoint gives each parent its own txid.
            txIn = listOf(TxIn(OutPoint(TxId(ByteVector32.fromValidHex((parents.size + 1).toString(16).padStart(64, '0'))), 0), 0xFFFFFFFFL)),
            txOut = listOf(decoy) + payments.map { (key, value) -> TxOut(Satoshi(value), ByteVector(converter.createScriptPubKey(key))) },
            lockTime = 0,
        )
        parents[parent.txid.toString()] = parent
        val utxos = payments.mapIndexed { i, (key, value) ->
            UnspentOutput(
                transactionHash = parent.txid.value,
                outputIndex = i + 1,
                value = value,
                scriptPubKey = converter.createScriptPubKey(key),
                scriptType = key.scriptType,
                publicKeyPath = key.path,
                blockHeight = 100,
            )
        }
        storage.unspentOutputStorage.saveUtxos(utxos)
        if (stored) storage.transactionStorage.saveTransaction(incoming(parent))
        return utxos
    }

    suspend fun externalKey(index: Int): WalletPublicKey = publicKeyManager.externalKeyAt(index)

    suspend fun firstChangeKey(): WalletPublicKey =
        publicKeyManager.getInternalPublicKeys().minBy { it.index }

    companion object {
        suspend fun create(config: WalletConfig): PsbtTestWallet {
            val hd = HDWalletManager.fromConfig(config)
            val converter = AddressConverter(config.network)
            val storage = InMemoryWalletStorage()
            // UTXOs are stamped at height 100; a tip at 110 makes them spendable.
            storage.blockInfoStorage.saveBlockInfo(BlockInfo(height = 110, hash = "h", timestamp = 0))
            val publicKeyManager = PublicKeyManager(HdWalletKeySource(hd), storage.publicKeyStorage, gapLimit = 20)
            publicKeyManager.initialize()
            val transactionCreator = TransactionCreator(
                hdWalletManager = hd,
                publicKeyManager = publicKeyManager,
                utxoProvider = UnspentOutputProvider(
                    storage = storage.unspentOutputStorage,
                    blockInfoStorage = storage.blockInfoStorage,
                    confirmationsThreshold = 1,
                ),
                addressConverter = converter,
                transactionStorage = storage.transactionStorage,
                unspentOutputStorage = storage.unspentOutputStorage,
            )
            val parents = mutableMapOf<String, Transaction>()
            val fetchedTxIds = mutableListOf<String>()
            val creator = PsbtCreator(config, transactionCreator, storage.transactionStorage) { txId ->
                fetchedTxIds += txId
                ByteVector(Transaction.write(parents.getValue(txId))).toHex()
            }
            return PsbtTestWallet(
                storage, publicKeyManager, converter, creator, parents, fetchedTxIds, mutableListOf(),
            ) { wallet ->
                PsbtFinalizer(transactionCreator) { rawTxHex ->
                    wallet.broadcasts += rawTxHex
                    wallet.broadcastFailure?.let { Result.failure(it) }
                        ?: Result.success(Transaction.read(rawTxHex).txid.toString())
                }
            }
        }
    }
}

internal fun incoming(tx: Transaction) = WalletTransaction(
    txId = tx.txid.toString(),
    transaction = tx,
    blockHeight = 100,
    status = TransactionStatus.CONFIRMED,
    type = TransactionType.INCOMING,
    amount = 0,
)

internal fun decode(draft: PsbtDraft): Psbt =
    Psbt.read(Base64.decode(draft.base64)).right ?: fail("PSBT does not parse")

/**
 * Sign every input the way a hardware wallet does: find the key through the
 * input's derivation field (checking the master fingerprint), derive it from
 * [TEST_MASTER], sign. Inputs stay unfinalized, as some signers return them.
 */
internal fun Psbt.signAll(): Psbt {
    var signed = this
    inputs.forEachIndexed { index, input ->
        val internalKey = input.taprootInternalKey
        signed = if (internalKey != null) {
            val derivation = input.taprootDerivationPaths.getValue(internalKey)
            signed.signInput(index, privateKeyFor(KeyPathWithMaster(derivation.masterKeyFingerprint, derivation.keyPath)))
        } else {
            val (publicKey, derivation) = input.derivationPaths.entries.single()
            val privateKey = privateKeyFor(derivation)
            assertEquals(publicKey, privateKey.publicKey())
            if (input is Input.WitnessInput) {
                // ACINQ signs with the PSBT's witness script as the BIP-143 script
                // code, but a P2WPKH input has none (BIP-174): a signer uses P2PKH.
                signed.updateWitnessInput(
                    outPoint = global.tx.txIn[index].outPoint,
                    txOut = input.txOut,
                    witnessScript = Script.pay2pkh(publicKey),
                ).right!!.signInput(index, privateKey)
            } else {
                signed.signInput(index, privateKey)
            }
        }
    }
    return signed
}

/** Finalize every signed input, as a signer that returns a finalized PSBT does. */
internal fun Psbt.finalizeAll(): Psbt {
    var finalized = this
    inputs.forEachIndexed { index, input ->
        val taprootSignature = input.taprootKeySignature
        val (publicKey, signature) = input.partialSigs.entries.singleOrNull()?.toPair() ?: (null to null)
        finalized = when {
            taprootSignature != null -> finalized.finalizeWitnessInput(index, ScriptWitness(listOf(taprootSignature)))
            input is Input.WitnessInput ->
                finalized.finalizeWitnessInput(index, ScriptWitness(listOf(signature!!, publicKey!!.value)))
            else -> finalized.finalizeNonWitnessInput(index, listOf(OP_PUSHDATA(signature!!), OP_PUSHDATA(publicKey!!.value)))
        }.right ?: fail("input $index does not finalize")
    }
    return finalized
}

private fun privateKeyFor(derivation: KeyPathWithMaster): PrivateKey {
    assertEquals(TEST_MASTER_FINGERPRINT, derivation.masterKeyFingerprint, "not this signer's key")
    return DeterministicWallet.derivePrivateKey(TEST_MASTER, derivation.keyPath).privateKey
}

private fun Psbt.signInput(index: Int, privateKey: PrivateKey): Psbt =
    sign(privateKey, index).right?.psbt ?: fail("input $index does not sign")
