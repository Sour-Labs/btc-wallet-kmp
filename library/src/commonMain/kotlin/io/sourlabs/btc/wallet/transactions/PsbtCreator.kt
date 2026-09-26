package io.sourlabs.btc.wallet.transactions

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.psbt.KeyPathWithMaster
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.psbt.TaprootBip32DerivationPath
import fr.acinq.bitcoin.psbt.UpdateFailure
import fr.acinq.bitcoin.utils.Either
import io.sourlabs.btc.wallet.api.PsbtException
import io.sourlabs.btc.wallet.core.WalletConfig
import io.sourlabs.btc.wallet.descriptors.Descriptor
import io.sourlabs.btc.wallet.descriptors.OutputDescriptor
import io.sourlabs.btc.wallet.models.ScriptType
import io.sourlabs.btc.wallet.models.UnspentOutput
import io.sourlabs.btc.wallet.models.WalletPublicKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.io.encoding.Base64

/**
 * An unsigned PSBT, ready for the hardware wallet that holds the wallet's keys.
 */
data class PsbtDraft(
    /**
     * The PSBT (BIP-174, version 0), base64-encoded.
     */
    val base64: String,

    /**
     * Amount the destination receives. For subtract-fee sends this is the
     * post-subtraction amount (requested amount − fee).
     */
    val amount: Long,

    /**
     * Fee paid.
     */
    val fee: Long
)

/**
 * Builds unsigned PSBTs for a single-key watch-only wallet.
 *
 * Every input and the change output carry a BIP-32 derivation: the master
 * fingerprint and full path from the descriptor's key origin. That is how a
 * signer finds its keys, and how it tells change apart from a payment.
 *
 * Segwit v0 and legacy inputs also carry their whole parent transaction
 * (NON_WITNESS_UTXO): since the 2020 segwit fee attack, hardware wallets check
 * input amounts against it. Taproot inputs don't need it, because a BIP-341
 * signature commits to the amount of every input.
 */
internal class PsbtCreator(
    private val walletConfig: WalletConfig,
    private val transactionCreator: TransactionCreator,
    private val fetchRawTransaction: suspend (txId: String) -> String
) {
    /**
     * Select coins as [TransactionCreator.buildUnsigned] does and wrap the result
     * in a PSBT. Changes no wallet state.
     */
    suspend fun build(
        toAddress: String,
        amount: Long,
        feeRate: Long,
        subtractFeeFromAmount: Boolean
    ): PsbtDraft {
        val keyOrigin = singleKeyOrigin()
        val unsignedTx = transactionCreator.buildUnsigned(
            toAddress = toAddress,
            amount = amount,
            feeRate = feeRate,
            subtractFeeFromAmount = subtractFeeFromAmount
        )
        val parentTxIds = unsignedTx.utxos.zip(unsignedTx.publicKeys)
            .filter { (_, key) -> key.scriptType != ScriptType.P2TR }
            .map { (utxo, _) -> utxo.toOutPoint().txid }
            .toSet()
        val parents = fetchParents(parentTxIds)

        var psbt = Psbt(unsignedTx.transaction)
        unsignedTx.utxos.zip(unsignedTx.publicKeys).forEach { (utxo, key) ->
            psbt = psbt.withInput(utxo, key, keyOrigin, parents[utxo.toOutPoint().txid])
        }
        val changeIndex = unsignedTx.changeOutputIndex
        val changeKey = unsignedTx.changeKey
        if (changeIndex != null && changeKey != null) {
            psbt = psbt.withChange(changeIndex, changeKey, keyOrigin)
        }

        val sendAmount = unsignedTx.transaction.txOut
            .filterIndexed { index, _ -> index != changeIndex }
            .sumOf { it.amount.sat }
        return PsbtDraft(
            base64 = Base64.encode(Psbt.write(psbt).toByteArray()),
            amount = sendAmount,
            fee = unsignedTx.fee
        )
    }

    private fun singleKeyOrigin(): Descriptor.KeyOrigin {
        val descriptor = (walletConfig as? WalletConfig.WatchOnlyDescriptor)?.parsedOutputDescriptor
        if (descriptor is OutputDescriptor.Multisig) throw PsbtException.MultisigNotSupported()
        return (descriptor as? OutputDescriptor.SingleKey)?.descriptor?.keyOrigin
            ?: throw PsbtException.MissingKeyOrigin()
    }

    private suspend fun fetchParents(txIds: Set<TxId>): Map<TxId, Transaction> = coroutineScope {
        txIds.map { txId -> async { txId to fetchParent(txId) } }.awaitAll().toMap()
    }

    private suspend fun fetchParent(txId: TxId): Transaction {
        val tx = try {
            Transaction.read(fetchRawTransaction(txId.toString()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PsbtException.ParentTransactionUnavailable(txId.toString(), e)
        }
        // A signer trusts the amounts in this transaction, so it must be the one
        // the input spends, whatever the explorer returned.
        if (tx.txid != txId) throw PsbtException.ParentTransactionUnavailable(txId.toString())
        return tx
    }

    private fun Psbt.withInput(
        utxo: UnspentOutput,
        key: WalletPublicKey,
        keyOrigin: Descriptor.KeyOrigin,
        parent: Transaction?
    ): Psbt {
        val derivation = keyOrigin.derivationOf(key)
        return when (key.scriptType) {
            ScriptType.P2WPKH -> updateWitnessInputTx(
                inputTx = requireNotNull(parent),
                outputIndex = utxo.outputIndex,
                derivationPaths = mapOf(key.publicKey to derivation)
            )
            ScriptType.P2SH_P2WPKH -> updateWitnessInputTx(
                inputTx = requireNotNull(parent),
                outputIndex = utxo.outputIndex,
                redeemScript = Script.pay2wpkh(key.publicKey),
                derivationPaths = mapOf(key.publicKey to derivation)
            )
            ScriptType.P2PKH -> updateNonWitnessInput(
                inputTx = requireNotNull(parent),
                outputIndex = utxo.outputIndex,
                derivationPaths = mapOf(key.publicKey to derivation)
            )
            ScriptType.P2TR -> {
                val internalKey = XonlyPublicKey(key.publicKey)
                updateWitnessInput(
                    outPoint = utxo.toOutPoint(),
                    txOut = utxo.toTxOut(),
                    taprootInternalKey = internalKey,
                    taprootDerivationPaths = mapOf(internalKey to derivation.forTaproot())
                )
            }
            ScriptType.P2SH, ScriptType.P2WSH -> error("${key.scriptType} input in a single-key wallet")
        }.orThrow()
    }

    private fun Psbt.withChange(
        outputIndex: Int,
        key: WalletPublicKey,
        keyOrigin: Descriptor.KeyOrigin
    ): Psbt {
        val derivation = keyOrigin.derivationOf(key)
        return when (key.scriptType) {
            ScriptType.P2WPKH -> updateWitnessOutput(
                outputIndex = outputIndex,
                derivationPaths = mapOf(key.publicKey to derivation)
            )
            ScriptType.P2SH_P2WPKH -> updateWitnessOutput(
                outputIndex = outputIndex,
                redeemScript = Script.pay2wpkh(key.publicKey),
                derivationPaths = mapOf(key.publicKey to derivation)
            )
            ScriptType.P2PKH -> updateNonWitnessOutput(
                outputIndex = outputIndex,
                derivationPaths = mapOf(key.publicKey to derivation)
            )
            ScriptType.P2TR -> {
                val internalKey = XonlyPublicKey(key.publicKey)
                updateWitnessOutput(
                    outputIndex = outputIndex,
                    taprootInternalKey = internalKey,
                    taprootDerivationPaths = mapOf(internalKey to derivation.forTaproot())
                )
            }
            ScriptType.P2SH, ScriptType.P2WSH -> error("${key.scriptType} change in a single-key wallet")
        }.orThrow()
    }
}

/**
 * The key's full path from the master: the origin path (which the account
 * extended public key sits at) followed by `/chain/index`.
 */
private fun Descriptor.KeyOrigin.derivationOf(key: WalletPublicKey): KeyPathWithMaster {
    val accountPath = path.map { step ->
        if (step.hardened) DeterministicWallet.hardened(step.index) else step.index
    }
    val chain = if (key.isExternal) 0L else 1L
    val masterFingerprint = fingerprint.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) }
    return KeyPathWithMaster(masterFingerprint, KeyPath(accountPath + chain + key.index.toLong()))
}

/** A key-path-only taproot key has no script leaves. */
private fun KeyPathWithMaster.forTaproot(): TaprootBip32DerivationPath =
    TaprootBip32DerivationPath(leaves = emptyList(), masterKeyFingerprint = masterKeyFingerprint, keyPath = keyPath)

// ACINQ refuses an update only when the PSBT doesn't match the data given,
// which the code above never produces.
private fun Either<UpdateFailure, Psbt>.orThrow(): Psbt = when (this) {
    is Either.Right -> value
    is Either.Left -> error("PSBT update failed: $value")
}
