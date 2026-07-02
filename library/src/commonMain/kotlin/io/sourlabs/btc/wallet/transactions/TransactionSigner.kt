package io.sourlabs.btc.wallet.transactions

import fr.acinq.bitcoin.*
import io.sourlabs.btc.wallet.keys.HDWalletManager
import io.sourlabs.btc.wallet.models.ScriptType
import io.sourlabs.btc.wallet.models.UnspentOutput
import io.sourlabs.btc.wallet.models.WalletPublicKey

/**
 * Signs transactions for all supported script types.
 */
class TransactionSigner(
    private val hdWalletManager: HDWalletManager
) {
    init {
        require(!hdWalletManager.isWatchOnly) {
            "Cannot sign transactions with a watch-only wallet"
        }
    }

    /**
     * Sign an unsigned transaction.
     *
     * @param unsignedTx the unsigned transaction to sign
     * @return the fully signed transaction
     */
    fun sign(unsignedTx: UnsignedTransaction): Transaction {
        var tx = unsignedTx.transaction

        // Sign each input
        for (i in unsignedTx.utxos.indices) {
            val utxo = unsignedTx.utxos[i]
            val publicKey = unsignedTx.publicKeys[i]

            tx = signInput(tx, i, utxo, publicKey, unsignedTx.utxos)
        }

        return tx
    }

    /**
     * Sign a single input.
     */
    private fun signInput(
        tx: Transaction,
        inputIndex: Int,
        utxo: UnspentOutput,
        walletKey: WalletPublicKey,
        allUtxos: List<UnspentOutput>
    ): Transaction {
        val privateKey = hdWalletManager.derivePrivateKey(
            isExternal = walletKey.isExternal,
            index = walletKey.index
        )

        return when (walletKey.scriptType) {
            ScriptType.P2PKH -> signP2PKH(tx, inputIndex, utxo, privateKey)
            ScriptType.P2SH_P2WPKH -> signP2SHP2WPKH(tx, inputIndex, utxo, privateKey)
            ScriptType.P2WPKH -> signP2WPKH(tx, inputIndex, utxo, privateKey)
            ScriptType.P2TR -> signP2TR(tx, inputIndex, utxo, privateKey, allUtxos)
            // Generic P2SH never appears on a wallet-owned key — the wallet only
            // generates P2SH_P2WPKH for nested SegWit. Reaching here means the key
            // was tagged wrong.
            ScriptType.P2SH -> error("Cannot sign generic P2SH input — wallet keys must be P2SH_P2WPKH")
            // P2WSH inputs come from multisig watch-only wallets. The signer is
            // never constructed for those (the require(!isWatchOnly) at line 16
            // throws first) — if we somehow get here, it's a programming error.
            ScriptType.P2WSH -> error(
                "Cannot sign P2WSH input from this wallet — multisig signing requires N cosigner " +
                    "signatures and is not implemented. Watch-only multisig wallets cannot send."
            )
        }
    }

    /**
     * Sign a P2PKH (legacy) input.
     */
    private fun signP2PKH(
        tx: Transaction,
        inputIndex: Int,
        utxo: UnspentOutput,
        privateKey: DeterministicWallet.ExtendedPrivateKey
    ): Transaction {
        val publicKey = privateKey.publicKey

        // Create the scriptPubKey being spent
        val scriptPubKey = Script.pay2pkh(publicKey)

        // Sign the input
        val sig = Transaction.signInput(
            tx,
            inputIndex,
            scriptPubKey,
            SigHash.SIGHASH_ALL,
            Satoshi(utxo.value),
            SigVersion.SIGVERSION_BASE,
            privateKey.privateKey
        )

        // Create the scriptSig: <sig> <pubkey>
        val scriptSig = ByteVector(Script.write(listOf(OP_PUSHDATA(ByteVector(sig)), OP_PUSHDATA(publicKey.value))))

        // Update the transaction input
        val updatedInputs = tx.txIn.toMutableList()
        updatedInputs[inputIndex] = tx.txIn[inputIndex].copy(signatureScript = scriptSig)

        return tx.copy(txIn = updatedInputs)
    }

    /**
     * Sign a P2SH-P2WPKH (nested SegWit) input.
     */
    private fun signP2SHP2WPKH(
        tx: Transaction,
        inputIndex: Int,
        utxo: UnspentOutput,
        privateKey: DeterministicWallet.ExtendedPrivateKey
    ): Transaction {
        val publicKey = privateKey.publicKey

        // The witness program (P2WPKH script)
        val witnessScript = Script.pay2wpkh(publicKey)

        // The scriptSig contains the witness program
        val scriptSig = ByteVector(Script.write(listOf(OP_PUSHDATA(ByteVector(Script.write(witnessScript))))))

        // Sign for witness
        val sig = Transaction.signInput(
            tx,
            inputIndex,
            Script.pay2pkh(publicKey), // Signing uses P2PKH equivalent
            SigHash.SIGHASH_ALL,
            Satoshi(utxo.value),
            SigVersion.SIGVERSION_WITNESS_V0,
            privateKey.privateKey
        )

        // Create witness
        val witness = ScriptWitness(listOf(ByteVector(sig), publicKey.value))

        // Update transaction
        val updatedInputs = tx.txIn.toMutableList()
        updatedInputs[inputIndex] = tx.txIn[inputIndex].copy(
            signatureScript = scriptSig,
            witness = witness
        )

        return tx.copy(txIn = updatedInputs)
    }

    /**
     * Sign a P2WPKH (native SegWit v0) input.
     */
    private fun signP2WPKH(
        tx: Transaction,
        inputIndex: Int,
        utxo: UnspentOutput,
        privateKey: DeterministicWallet.ExtendedPrivateKey
    ): Transaction {
        val publicKey = privateKey.publicKey

        // Sign for witness
        val sig = Transaction.signInput(
            tx,
            inputIndex,
            Script.pay2pkh(publicKey), // Signing uses P2PKH equivalent
            SigHash.SIGHASH_ALL,
            Satoshi(utxo.value),
            SigVersion.SIGVERSION_WITNESS_V0,
            privateKey.privateKey
        )

        // Create witness: <sig> <pubkey>
        val witness = ScriptWitness(listOf(ByteVector(sig), publicKey.value))

        val updatedInputs = tx.txIn.toMutableList()
        updatedInputs[inputIndex] = tx.txIn[inputIndex].copy(witness = witness)

        return tx.copy(txIn = updatedInputs)
    }

    /**
     * Sign a P2TR (Taproot) input using key path spending.
     */
    private fun signP2TR(
        tx: Transaction,
        inputIndex: Int,
        utxo: UnspentOutput,
        privateKey: DeterministicWallet.ExtendedPrivateKey,
        allUtxos: List<UnspentOutput>
    ): Transaction {
        // Get all previous outputs for signing
        val prevOuts = allUtxos.map { u ->
            TxOut(Satoshi(u.value), ByteVector(u.scriptPubKey))
        }

        // Sign using Schnorr signature. Pass the *untweaked* private key:
        // signInputTaprootKeyPath applies the BIP-341 key-path tweak itself
        // when scriptTree is null.
        val sig = Transaction.signInputTaprootKeyPath(
            privateKey.privateKey,
            tx,
            inputIndex,
            prevOuts,
            SigHash.SIGHASH_DEFAULT,
            null // No script tree for key-path spending
        )

        // Create witness with just the signature (64 bytes for SIGHASH_DEFAULT)
        val witness = ScriptWitness(listOf(sig))

        val updatedInputs = tx.txIn.toMutableList()
        updatedInputs[inputIndex] = tx.txIn[inputIndex].copy(witness = witness)

        return tx.copy(txIn = updatedInputs)
    }
}
