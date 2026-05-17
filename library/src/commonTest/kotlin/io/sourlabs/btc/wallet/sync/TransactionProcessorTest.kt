package io.sourlabs.btc.wallet.sync

import fr.acinq.bitcoin.PublicKey
import io.sourlabs.btc.wallet.keys.AddressConverter
import io.sourlabs.btc.wallet.keys.HDWalletManager
import io.sourlabs.btc.wallet.keys.PublicKeyManager
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import io.sourlabs.btc.wallet.models.ScriptType
import io.sourlabs.btc.wallet.models.TransactionStatus
import io.sourlabs.btc.wallet.models.TransactionType
import io.sourlabs.btc.wallet.storage.InMemoryWalletStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Anchors PR-04 (audit finding H2). After splitting [ScriptType.P2SH] from
 * [ScriptType.P2SH_P2WPKH], parseAddress reports a generic [ScriptType.P2SH]
 * for any `3...` address — but a BIP-49 wallet's own keys are still tagged
 * [ScriptType.P2SH_P2WPKH]. The matching path in TransactionProcessor must
 * recognize the wallet's own outputs even though the script types don't
 * literally match.
 */
class TransactionProcessorTest {

    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    private fun ByteArray.toHex(): String = joinToString("") {
        val v = it.toInt() and 0xFF
        val hex = v.toString(16)
        if (hex.length == 1) "0$hex" else hex
    }

    @Test
    fun bip49WalletRecognizesIncomingTxToOwnNestedSegwitAddress() = runTest {
        val hd = HDWalletManager.fromMnemonic(testMnemonic, "", Purpose.BIP49, Network.MAINNET)
        val converter = AddressConverter(Network.MAINNET)
        val storage = InMemoryWalletStorage()
        val pkm = PublicKeyManager(hd, storage.publicKeyStorage, gapLimit = 5)
        pkm.initialize()

        val externalKey0 = pkm.getExternalPublicKeys().sortedBy { it.index }.first()
        val ownAddress = converter.toP2SHP2WPKHAddress(externalKey0.publicKey)
        val ownScriptPubKey = converter.createScriptPubKey(externalKey0.publicKey, ScriptType.P2SH_P2WPKH)

        // Synthetic incoming tx: someone sends us 100_000 sats to our own `3...` address.
        // The vin's prevout is from a foreign address (not ours).
        val incomingTx = ApiTransaction(
            txid = "1".repeat(64),
            version = 2,
            locktime = 0,
            vin = listOf(
                ApiVin(
                    txid = "0".repeat(64),
                    vout = 0,
                    prevout = ApiVout(
                        scriptPubKey = "00",
                        scriptPubKeyAddress = "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2",
                        value = 200_000,
                    ),
                )
            ),
            vout = listOf(
                ApiVout(
                    scriptPubKey = ownScriptPubKey.toHex(),
                    scriptPubKeyAddress = ownAddress,
                    value = 100_000,
                )
            ),
            size = 200,
            weight = 800,
            fee = 1_000,
            status = ApiTxStatus(confirmed = true, blockHeight = 100, blockTime = 12_345L),
        )

        val processor = TransactionProcessor(
            publicKeyManager = pkm,
            transactionStorage = storage.transactionStorage,
            utxoStorage = storage.unspentOutputStorage,
            addressConverter = converter,
        )

        val processed = processor.processTransactions(
            address = ownAddress,
            transactions = listOf(incomingTx),
            currentBlockHeight = 100,
        )

        assertEquals(1, processed.size, "wallet must recognize the incoming tx as ours")
        val tx = processed[0]
        assertEquals(TransactionType.INCOMING, tx.type)
        assertEquals(100_000L, tx.amount)
        assertEquals(TransactionStatus.CONFIRMED, tx.status)
    }

    /**
     * Counts createScriptPubKey invocations so we can assert the processor
     * built its key index once instead of recomputing the scriptPubKey for
     * every key on every lookup.
     */
    private class CountingAddressConverter(network: Network) : AddressConverter(network) {
        var createScriptPubKeyCalls = 0
            private set

        override fun createScriptPubKey(publicKey: PublicKey, scriptType: ScriptType): ByteArray {
            createScriptPubKeyCalls++
            return super.createScriptPubKey(publicKey, scriptType)
        }
    }

    /**
     * Anchors PR-10 (audit finding H9). The pre-PR-10 findWalletKey path called
     * createScriptPubKey for every wallet key on every P2SH / P2TR vout lookup,
     * making the per-tx cost O(keys × vouts). After PR-10 the processor builds a
     * scriptPubKey index once per address and then does O(1) map lookups.
     *
     * With 20 keys and 8 vouts hitting the script-pubkey path, the old code would
     * call createScriptPubKey at least 20 × 8 = 160 times. The new code calls it
     * exactly 20 times (once per key during the one-shot index build), and
     * critically NOT proportional to the vout count.
     */
    @Test
    fun findWalletKeyDoesNotRescanKeysPerVout() = runTest {
        val hd = HDWalletManager.fromMnemonic(testMnemonic, "", Purpose.BIP49, Network.MAINNET)
        val converter = CountingAddressConverter(Network.MAINNET)
        val storage = InMemoryWalletStorage()
        val pkm = PublicKeyManager(hd, storage.publicKeyStorage, gapLimit = 20)
        pkm.initialize()  // fills 20 external + 20 internal keys

        val totalKeys = pkm.getAllPublicKeys().size
        assertEquals(40, totalKeys, "fixture should derive 40 keys (20 external + 20 internal)")

        // Reset the counter — initialize() may have invoked createScriptPubKey
        // for internal wallet bookkeeping (it doesn't today, but we want a clean
        // baseline against the index-build phase regardless).
        val baseline = converter.createScriptPubKeyCalls

        val processor = TransactionProcessor(
            publicKeyManager = pkm,
            transactionStorage = storage.transactionStorage,
            utxoStorage = storage.unspentOutputStorage,
            addressConverter = converter,
        )

        // 8 fake txs each with a P2SH vout that doesn't match any of our keys.
        // P2SH falls through to the scriptPubKey index (extractPubKeyHash returns
        // null for P2SH), so each vout exercises the heavy path.
        val unknownP2shScript = "a91400112233445566778899aabbccddeeff0011223387"  // OP_HASH160 <20> OP_EQUAL
        val txs = (0 until 8).map { i ->
            ApiTransaction(
                txid = i.toString().repeat(64).take(64),
                version = 2, locktime = 0,
                vin = emptyList(),
                vout = listOf(
                    ApiVout(
                        scriptPubKey = unknownP2shScript,
                        scriptPubKeyAddress = "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy",
                        value = 100,
                    )
                ),
                size = 100, weight = 400, fee = 100,
                status = ApiTxStatus(confirmed = true, blockHeight = 1, blockTime = 0),
            )
        }

        processor.processTransactions(
            address = "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy",
            transactions = txs,
            currentBlockHeight = 1,
        )

        val callsDuringProcessing = converter.createScriptPubKeyCalls - baseline
        // The index build is exactly one createScriptPubKey call per wallet key.
        // Anything proportional to the vout count would indicate the old per-vout
        // rescan slipped back in.
        assertEquals(
            totalKeys,
            callsDuringProcessing,
            "scriptPubKey index should be built once per processor (one call per key); " +
                "got $callsDuringProcessing calls for $totalKeys keys and ${txs.size} vouts",
        )
        // Belt-and-suspenders: explicitly check we're not scaling with vouts.
        assertTrue(
            callsDuringProcessing < totalKeys * 2,
            "calls scaled with vouts — PR-10 index rebuild regression",
        )
    }
}
