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
import io.sourlabs.btc.wallet.storage.TransactionStorage
import kotlinx.coroutines.CancellationException
import kotlin.io.encoding.Base64

/**
 * An unsigned PSBT, ready for the hardware wallet that holds the wallet's keys.
 *
 * Keep it to pass to [io.sourlabs.btc.wallet.api.BitcoinKit.finalizeSigned]
 * with what the signer returns: it carries the UTXOs, keys and change key the
 * wallet chose, which the signed result is checked against. Only
 * [io.sourlabs.btc.wallet.api.BitcoinKit.buildPsbt] creates one; it can't be
 * rebuilt from [base64].
 */
class PsbtDraft internal constructor(
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
    val fee: Long,

    internal val psbt: Psbt,
    internal val spentUtxos: List<UnspentOutput>,
    internal val inputKeys: List<WalletPublicKey>,
    internal val changeKey: WalletPublicKey?,
    internal val walletConfig: WalletConfig
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
 * input amounts against it. Parents come from local storage when sync saved
 * them, else from the explorer. Taproot inputs don't need a parent, because a
 * BIP-341 signature commits to the amount of every input.
 */
internal class PsbtCreator(
    private val walletConfig: WalletConfig,
    private val transactionCreator: TransactionCreator,
    private val transactionStorage: TransactionStorage,
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
        // One at a time: after a sync, parents are in storage, and a burst of
        // requests to a public explorer gets rate-limited (HTTP 429 isn't retried).
        val parents = unsignedTx.utxos.zip(unsignedTx.publicKeys)
            .filter { (_, key) -> key.scriptType != ScriptType.P2TR }
            .map { (utxo, _) -> utxo.toOutPoint().txid }
            .toSet()
            .associateWith { txId -> parentTransaction(txId) }

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
            fee = unsignedTx.fee,
            psbt = psbt,
            spentUtxos = unsignedTx.utxos,
            inputKeys = unsignedTx.publicKeys,
            changeKey = unsignedTx.changeKey,
            walletConfig = walletConfig
        )
    }

    /**
     * The key origin a signer finds its keys through. It must describe the
     * account key the descriptor holds: one path step per BIP-32 level
     * (HDWalletManager requires depth 3), ending at that key's child number.
     */
    private fun singleKeyOrigin(): Descriptor.KeyOrigin {
        val descriptor = (walletConfig as? WalletConfig.WatchOnlyDescriptor)?.parsedOutputDescriptor
        if (descriptor is OutputDescriptor.Multisig) throw PsbtException.MultisigNotSupported()
        val singleKey = (descriptor as? OutputDescriptor.SingleKey)?.descriptor
        val keyOrigin = singleKey?.keyOrigin ?: throw PsbtException.MissingKeyOrigin()
        val accountKey = DeterministicWallet.ExtendedPublicKey.decode(singleKey.extendedPublicKey).second
        val originPath = keyOrigin.path.map { it.childNumber() }
        if (originPath.size != accountKey.depth || originPath.lastOrNull() != accountKey.path.lastChildNumber) {
            throw PsbtException.KeyOriginMismatch(keyOrigin.toString())
        }
        return keyOrigin
    }

    private suspend fun parentTransaction(txId: TxId): Transaction {
        // Stored transactions were rebuilt from explorer data; a wrong one is
        // fetched again rather than trusted.
        transactionStorage.getTransaction(txId.toString())?.transaction
            ?.takeIf { it.txid == txId }
            ?.let { return it }
        val tx = try {
            Transaction.read(fetchRawTransaction(txId.toString()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PsbtException.ParentTransactionUnavailable(txId.toString(), e)
        }
        if (tx.txid != txId) throw PsbtException.ParentTransactionMismatch(txId.toString())
        return tx
    }

    private fun Psbt.withInput(
        utxo: UnspentOutput,
        key: WalletPublicKey,
        keyOrigin: Descriptor.KeyOrigin,
        parent: Transaction?
    ): Psbt {
        // Fee and change were computed from the stored UTXO; the signer checks
        // amounts against the parent, so the two must agree.
        if (parent != null && parent.txOut.getOrNull(utxo.outputIndex) != utxo.toTxOut()) {
            throw PsbtException.ParentTransactionMismatch(parent.txid.toString())
        }
        val fields = SignerFields(key, keyOrigin)
        return when (key.scriptType) {
            ScriptType.P2PKH -> updateNonWitnessInput(
                inputTx = requireNotNull(parent),
                outputIndex = utxo.outputIndex,
                derivationPaths = fields.derivationPaths
            )
            ScriptType.P2WPKH, ScriptType.P2SH_P2WPKH -> updateWitnessInputTx(
                inputTx = requireNotNull(parent),
                outputIndex = utxo.outputIndex,
                redeemScript = fields.redeemScript,
                derivationPaths = fields.derivationPaths
            )
            ScriptType.P2TR -> updateWitnessInput(
                outPoint = utxo.toOutPoint(),
                txOut = utxo.toTxOut(),
                taprootInternalKey = fields.taprootInternalKey,
                taprootDerivationPaths = fields.taprootDerivationPaths
            )
            ScriptType.P2SH, ScriptType.P2WSH -> error("${key.scriptType} input in a single-key wallet")
        }.orThrow()
    }

    private fun Psbt.withChange(
        outputIndex: Int,
        key: WalletPublicKey,
        keyOrigin: Descriptor.KeyOrigin
    ): Psbt {
        val fields = SignerFields(key, keyOrigin)
        return when (key.scriptType) {
            ScriptType.P2PKH -> updateNonWitnessOutput(
                outputIndex = outputIndex,
                derivationPaths = fields.derivationPaths
            )
            ScriptType.P2WPKH, ScriptType.P2SH_P2WPKH, ScriptType.P2TR -> updateWitnessOutput(
                outputIndex = outputIndex,
                redeemScript = fields.redeemScript,
                derivationPaths = fields.derivationPaths,
                taprootInternalKey = fields.taprootInternalKey,
                taprootDerivationPaths = fields.taprootDerivationPaths
            )
            ScriptType.P2SH, ScriptType.P2WSH -> error("${key.scriptType} change in a single-key wallet")
        }.orThrow()
    }
}

/**
 * The PSBT fields through which a signer recognises [key] as its own, the same
 * for an input and for change: a BIP-32 derivation, plus the redeem script for
 * wrapped segwit, or the internal key and taproot derivation for taproot.
 */
private class SignerFields(key: WalletPublicKey, keyOrigin: Descriptor.KeyOrigin) {
    private val derivation = KeyPathWithMaster(
        keyOrigin.fingerprint.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) },
        // The origin path leads to the account key; `/chain/index` leads on to this key.
        KeyPath(keyOrigin.path.map { it.childNumber() } + (if (key.isExternal) 0L else 1L) + key.index.toLong())
    )
    private val isTaproot = key.scriptType == ScriptType.P2TR

    val derivationPaths = if (isTaproot) emptyMap() else mapOf(key.publicKey to derivation)
    val redeemScript = if (key.scriptType == ScriptType.P2SH_P2WPKH) Script.pay2wpkh(key.publicKey) else null
    val taprootInternalKey = if (isTaproot) XonlyPublicKey(key.publicKey) else null
    // A key-path-only taproot key has no script leaves.
    val taprootDerivationPaths = taprootInternalKey
        ?.let { mapOf(it to TaprootBip32DerivationPath(emptyList(), derivation.masterKeyFingerprint, derivation.keyPath)) }
        .orEmpty()
}

private fun Descriptor.PathStep.childNumber(): Long =
    if (hardened) DeterministicWallet.hardened(index) else index

// ACINQ refuses an update only when the PSBT doesn't match the data given,
// which the code above never produces.
private fun Either<UpdateFailure, Psbt>.orThrow(): Psbt = when (this) {
    is Either.Right -> value
    is Either.Left -> error("PSBT update failed: $value")
}
