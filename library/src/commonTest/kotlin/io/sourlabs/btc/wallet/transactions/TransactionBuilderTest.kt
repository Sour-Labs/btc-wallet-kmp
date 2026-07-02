package io.sourlabs.btc.wallet.transactions

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Satoshi
import io.sourlabs.btc.wallet.keys.AddressConverter
import io.sourlabs.btc.wallet.keys.HDWalletManager
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import io.sourlabs.btc.wallet.models.ScriptType
import io.sourlabs.btc.wallet.models.UnspentOutput
import io.sourlabs.btc.wallet.models.WalletPublicKey
import io.sourlabs.btc.wallet.utxo.SelectionResult
import kotlin.test.Test
import kotlin.test.assertEquals

class TransactionBuilderTest {
    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )
    private val wallet = HDWalletManager.fromMnemonic(testMnemonic, "", Purpose.BIP84, Network.MAINNET)
    private val builder = TransactionBuilder(AddressConverter(Network.MAINNET))

    private fun createWalletPublicKey(isExternal: Boolean, index: Int): WalletPublicKey {
        val publicKey = wallet.derivePublicKey(isExternal, index)
        val path = wallet.getDerivationPath(isExternal, index)
        val hash = Crypto.hash160(publicKey.value)
        return WalletPublicKey(
            path = path,
            purpose = wallet.purpose,
            account = wallet.account,
            isExternal = isExternal,
            index = index,
            publicKey = publicKey,
            publicKeyHash = hash,
            isUsed = false
        )
    }

    private fun createUtxo(value: Long): UnspentOutput {
        return UnspentOutput(
            transactionHash = ByteVector32.Zeroes,
            outputIndex = 0,
            value = value,
            scriptPubKey = ByteArray(22),
            scriptType = ScriptType.P2WPKH,
            publicKeyPath = wallet.getDerivationPath(true, 0),
            blockHeight = 100,
            isSpendable = true
        )
    }

    /**
     * The builder emits SelectionResult.sendAmount verbatim — for subtract-fee
     * sends the selector already computed the post-subtraction amount, and
     * re-deriving it in the builder is what double-counted the fee (crypto
     * review F2).
     */
    @Test
    fun testSubtractFeeUsesSelectionAmountsVerbatim() {
        val utxo = createUtxo(110_000)
        // Subtract-fee selection for target 100_000: sendAmount is already
        // target − fee, change is totalInput − target.
        val selection = SelectionResult(
            selectedUtxos = listOf(utxo),
            totalInput = 110_000,
            sendAmount = 98_000,
            fee = 2_000,
            change = 10_000
        )

        val inputKey = createWalletPublicKey(true, 0)
        val changeKey = createWalletPublicKey(false, 0)
        val params = TransactionParams(
            toAddress = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            amount = 100_000,
            feeRate = 1,
            subtractFeeFromAmount = true
        )

        val unsignedTx = builder.build(selection, params, changeKey, listOf(inputKey))
        val outputs = unsignedTx.transaction.txOut

        assertEquals(2, outputs.size)
        assertEquals(Satoshi(98_000), outputs[0].amount)
        assertEquals(Satoshi(10_000), outputs[1].amount)
        assertEquals(2_000, unsignedTx.fee)
    }

    /**
     * No-change subtract-fee selection: the fee already absorbed the sub-dust
     * residual, so the destination must get sendAmount as-is. The pre-fix
     * builder would have paid the residual-inflated fee out of the destination
     * a second time (amount − fee = 98_290 instead of 98_590).
     */
    @Test
    fun testSubtractFeeNoChangeDoesNotDoubleCountFee() {
        val utxo = createUtxo(100_000)
        val selection = SelectionResult(
            selectedUtxos = listOf(utxo),
            totalInput = 100_000,
            sendAmount = 98_590,
            fee = 1_410, // feeWithoutChange + 300 sub-dust residual
            change = 0
        )

        val inputKey = createWalletPublicKey(true, 0)
        val params = TransactionParams(
            toAddress = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            amount = 99_700,
            feeRate = 10,
            subtractFeeFromAmount = true
        )

        val unsignedTx = builder.build(selection, params, changeKey = null, listOf(inputKey))
        val outputs = unsignedTx.transaction.txOut

        assertEquals(1, outputs.size)
        assertEquals(Satoshi(98_590), outputs[0].amount)
        assertEquals(1_410, unsignedTx.fee)
        // Invariant: destination + fee == totalInput (no change output).
        assertEquals(selection.totalInput, outputs[0].amount.toLong() + unsignedTx.fee)
    }
}
