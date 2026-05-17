package io.sourlabs.btc.wallet.sync

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
}
