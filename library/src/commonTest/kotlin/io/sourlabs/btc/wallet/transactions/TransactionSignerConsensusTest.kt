package io.sourlabs.btc.wallet.transactions

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import io.sourlabs.btc.wallet.keys.AddressConverter
import io.sourlabs.btc.wallet.keys.HDWalletManager
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import io.sourlabs.btc.wallet.models.ScriptType
import io.sourlabs.btc.wallet.models.UnspentOutput
import io.sourlabs.btc.wallet.models.WalletPublicKey
import kotlin.test.Test

/**
 * Verifies that signed transactions actually satisfy Bitcoin consensus rules,
 * using bitcoin-kmp's script interpreter ([Transaction.correctlySpends]) as the
 * oracle. Address- and amount-level tests can't catch an invalid signature —
 * these can.
 */
class TransactionSignerConsensusTest {
    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )
    private val converter = AddressConverter(Network.MAINNET)

    private fun createWalletPublicKey(
        wallet: HDWalletManager,
        isExternal: Boolean,
        index: Int
    ): WalletPublicKey {
        val publicKey = wallet.derivePublicKey(isExternal, index)
        return WalletPublicKey(
            path = wallet.getDerivationPath(isExternal, index),
            purpose = wallet.purpose,
            account = wallet.account,
            isExternal = isExternal,
            index = index,
            publicKey = publicKey,
            publicKeyHash = Crypto.hash160(publicKey.value)
        )
    }

    private fun createUtxo(
        walletKey: WalletPublicKey,
        scriptType: ScriptType,
        txHashByte: String
    ): UnspentOutput {
        return UnspentOutput(
            transactionHash = ByteVector32.fromValidHex(txHashByte.repeat(32)),
            outputIndex = 0,
            value = 100_000L,
            scriptPubKey = converter.createScriptPubKey(walletKey.publicKey, scriptType),
            scriptType = scriptType,
            publicKeyPath = walletKey.path,
            blockHeight = 1
        )
    }

    /**
     * Build a spend of synthetic UTXOs paying to the wallet's own derived
     * script, sign it, and assert consensus validity. [Transaction.correctlySpends]
     * throws on any invalid input — that throw is the assertion.
     */
    private fun assertSignedSpendIsConsensusValid(purpose: Purpose, inputCount: Int = 1) {
        val wallet = HDWalletManager.fromMnemonic(testMnemonic, "", purpose, Network.MAINNET)
        val scriptType = ScriptType.fromPurpose(purpose)

        val walletKeys = (0 until inputCount).map { createWalletPublicKey(wallet, true, it) }
        val utxos = walletKeys.mapIndexed { i, key ->
            createUtxo(key, scriptType, "${i + 1}${i + 1}")
        }

        val destinationScript = ByteVector(converter.createScriptPubKey(walletKeys[0].publicKey, scriptType))
        val tx = Transaction(
            version = 2,
            txIn = utxos.map { TxIn(it.toOutPoint(), ByteVector.empty, 0xFFFFFFFDL) },
            txOut = listOf(TxOut(Satoshi(utxos.sumOf { it.value } - 10_000L), destinationScript)),
            lockTime = 0
        )
        val unsigned = UnsignedTransaction(tx, utxos, walletKeys, false, null, 10_000L)

        val signed = TransactionSigner(wallet).sign(unsigned)

        signed.correctlySpends(
            utxos.associate { it.toOutPoint() to it.toTxOut() },
            ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS
        )
    }

    @Test
    fun testP2PKHSpendIsConsensusValid() {
        assertSignedSpendIsConsensusValid(Purpose.BIP44)
    }

    @Test
    fun testP2SHP2WPKHSpendIsConsensusValid() {
        assertSignedSpendIsConsensusValid(Purpose.BIP49)
    }

    @Test
    fun testP2WPKHSpendIsConsensusValid() {
        assertSignedSpendIsConsensusValid(Purpose.BIP84)
    }

    @Test
    fun testP2TRSpendIsConsensusValid() {
        assertSignedSpendIsConsensusValid(Purpose.BIP86)
    }

    @Test
    fun testTwoInputP2TRSpendIsConsensusValid() {
        // The BIP-341 sighash commits to all prevouts in input order — a
        // second input catches any ordering mismatch between utxos and txIn.
        assertSignedSpendIsConsensusValid(Purpose.BIP86, inputCount = 2)
    }
}
