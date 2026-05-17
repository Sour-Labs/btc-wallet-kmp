package io.sourlabs.btc.wallet.sync

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import io.sourlabs.btc.wallet.keys.AddressConverter
import io.sourlabs.btc.wallet.keys.PublicKeyManager
import io.sourlabs.btc.wallet.models.*
import io.sourlabs.btc.wallet.storage.TransactionStorage
import io.sourlabs.btc.wallet.storage.UnspentOutputStorage

/**
 * Processes transactions and UTXOs from the API and updates storage.
 */
class TransactionProcessor(
    private val publicKeyManager: PublicKeyManager,
    private val transactionStorage: TransactionStorage,
    private val utxoStorage: UnspentOutputStorage,
    private val addressConverter: AddressConverter
) {
    /**
     * Process transactions for an address and update storage.
     * @return list of new or updated transactions
     */
    suspend fun processTransactions(
        address: String,
        transactions: List<ApiTransaction>,
        currentBlockHeight: Int
    ): List<WalletTransaction> {
        val processedTxs = mutableListOf<WalletTransaction>()

        for (apiTx in transactions) {
            val walletTx = processTransaction(apiTx, currentBlockHeight)
            if (walletTx != null) {
                transactionStorage.saveTransaction(walletTx)
                processedTxs.add(walletTx)
            }
        }

        return processedTxs
    }

    /**
     * Process UTXOs for an address and update storage.
     * @return list of new UTXOs
     */
    suspend fun processUtxos(
        address: String,
        utxos: List<ApiUtxo>,
        publicKeyPath: String,
        scriptType: ScriptType,
        currentBlockHeight: Int
    ): List<UnspentOutput> {
        val processedUtxos = mutableListOf<UnspentOutput>()

        // Get current UTXOs for this address to detect spent ones
        val existingUtxos = utxoStorage.getUtxosForKey(publicKeyPath)
        val newUtxoIds = utxos.map { "${it.txid}:${it.vout}" }.toSet()

        // Remove spent UTXOs
        for (existing in existingUtxos) {
            if (existing.id !in newUtxoIds) {
                utxoStorage.deleteUtxo(existing.id)
            }
        }

        // Add/update UTXOs
        for (apiUtxo in utxos) {
            val confirmations = if (apiUtxo.status.confirmed && apiUtxo.status.blockHeight != null) {
                currentBlockHeight - apiUtxo.status.blockHeight + 1
            } else {
                0
            }

            val scriptPubKey = addressConverter.addressToScriptPubKey(address)
                ?: continue

            val utxo = UnspentOutput(
                transactionHash = ByteVector32.fromValidHex(apiUtxo.txid),
                outputIndex = apiUtxo.vout,
                value = apiUtxo.value,
                scriptPubKey = scriptPubKey,
                scriptType = scriptType,
                confirmations = confirmations,
                publicKeyPath = publicKeyPath,
                blockHeight = apiUtxo.status.blockHeight,
                isSpendable = true
            )

            utxoStorage.saveUtxo(utxo)
            processedUtxos.add(utxo)
        }

        return processedUtxos
    }

    /**
     * Process a single transaction and determine wallet impact.
     */
    private suspend fun processTransaction(
        apiTx: ApiTransaction,
        currentBlockHeight: Int
    ): WalletTransaction? {
        // Check if we already have this transaction
        val existing = transactionStorage.getTransaction(apiTx.txid)
        if (existing != null) {
            // Update confirmation status if needed
            if (!existing.isConfirmed && apiTx.status.confirmed) {
                transactionStorage.updateConfirmation(
                    apiTx.txid,
                    apiTx.status.blockHeight ?: 0,
                    apiTx.status.blockTime ?: 0
                )
            }
            return null // Already processed
        }

        // Calculate net amount for the wallet
        var inputAmount = 0L
        var outputAmount = 0L
        var isOurs = false

        // Check inputs (money leaving)
        for (vin in apiTx.vin) {
            val prevout = vin.prevout ?: continue
            val prevAddress = prevout.scriptPubKeyAddress ?: continue

            // Check if this input is from our wallet
            val addressInfo = addressConverter.parseAddress(prevAddress)
            if (addressInfo != null) {
                val key = findWalletKey(addressInfo.scriptPubKey, addressInfo.scriptType)
                if (key != null) {
                    inputAmount += prevout.value
                    isOurs = true
                }
            }
        }

        // Check outputs (money arriving)
        for (vout in apiTx.vout) {
            val address = vout.scriptPubKeyAddress ?: continue

            val addressInfo = addressConverter.parseAddress(address)
            if (addressInfo != null) {
                val key = findWalletKey(addressInfo.scriptPubKey, addressInfo.scriptType)
                if (key != null) {
                    outputAmount += vout.value
                    isOurs = true
                }
            }
        }

        if (!isOurs) return null

        // Determine transaction type and amount
        val netAmount = outputAmount - inputAmount
        val type = when {
            inputAmount == 0L -> TransactionType.INCOMING
            outputAmount == 0L || netAmount < 0 -> TransactionType.OUTGOING
            else -> TransactionType.SELF
        }

        val status = if (apiTx.status.confirmed) {
            TransactionStatus.CONFIRMED
        } else {
            TransactionStatus.RELAYED
        }

        val transaction = buildTransaction(apiTx)

        return WalletTransaction(
            txId = apiTx.txid,
            transaction = transaction,
            blockHeight = apiTx.status.blockHeight,
            timestamp = apiTx.status.blockTime,
            status = status,
            type = type,
            amount = netAmount,
            fee = if (type != TransactionType.INCOMING) apiTx.fee else null
        )
    }

    private fun buildTransaction(apiTx: ApiTransaction): Transaction {
        val inputs = apiTx.vin.map { buildInput(it) }
        val outputs = apiTx.vout.map { buildOutput(it) }
        return Transaction(
            version = apiTx.version.toLong(),
            txIn = inputs,
            txOut = outputs,
            lockTime = apiTx.locktime
        )
    }

    private fun buildInput(vin: ApiVin): TxIn {
        val outPoint = buildOutPoint(vin.txid, vin.vout)
        val scriptSig = ByteVector(hexToByteArray(vin.scriptSig))
        val witness = ScriptWitness(
            vin.witness.orEmpty().map { ByteVector(hexToByteArray(it)) }
        )

        return TxIn(
            outPoint = outPoint,
            signatureScript = scriptSig,
            sequence = vin.sequence,
            witness = witness
        )
    }

    private fun buildOutput(vout: ApiVout): TxOut {
        val scriptPubKey = ByteVector(hexToByteArray(vout.scriptPubKey))
        return TxOut(Satoshi(vout.value), scriptPubKey)
    }

    private fun buildOutPoint(txIdHex: String?, vout: Int?): OutPoint {
        val txId = if (txIdHex != null && vout != null) {
            TxId(runCatching { ByteVector32.fromValidHex(txIdHex) }.getOrElse { ZERO_HASH })
        } else {
            TxId(ZERO_HASH)
        }
        val index = vout?.toLong() ?: COINBASE_OUTPOINT_INDEX
        return OutPoint(txId, index)
    }

    private fun hexToByteArray(hex: String): ByteArray {
        val cleanHex = hex.trim().removePrefix("0x")
        if (cleanHex.isEmpty()) return ByteArray(0)

        val evenHex = if (cleanHex.length % 2 == 0) cleanHex else "0$cleanHex"
        val result = ByteArray(evenHex.length / 2)
        var i = 0
        while (i < evenHex.length) {
            val high = evenHex[i].digitToIntOrNull(16) ?: 0
            val low = evenHex[i + 1].digitToIntOrNull(16) ?: 0
            result[i / 2] = ((high shl 4) or low).toByte()
            i += 2
        }
        return result
    }

    private companion object {
        private const val COINBASE_OUTPOINT_INDEX = 0xFFFFFFFFL
        private val ZERO_HASH = ByteVector32.fromValidHex(
            "0000000000000000000000000000000000000000000000000000000000000000"
        )
    }

    private suspend fun findWalletKey(scriptPubKey: ByteArray, scriptType: ScriptType): WalletPublicKey? {
        val pubKeyHash = extractPubKeyHash(scriptPubKey, scriptType)
        return if (pubKeyHash != null) {
            publicKeyManager.findByPublicKeyHash(pubKeyHash)
        } else {
            findByScriptPubKey(scriptPubKey, scriptType)
        }
    }

    private suspend fun findByScriptPubKey(scriptPubKey: ByteArray, scriptType: ScriptType): WalletPublicKey? {
        val keys = publicKeyManager.getAllPublicKeys()
        for (key in keys) {
            if (key.scriptType != scriptType) continue
            val keyScriptPubKey = addressConverter.createScriptPubKey(key.publicKey, key.scriptType)
            if (keyScriptPubKey.contentEquals(scriptPubKey)) {
                return key
            }
        }
        return null
    }

    /**
     * Extract pubkey hash from scriptPubKey when the script embeds it directly.
     */
    private fun extractPubKeyHash(scriptPubKey: ByteArray, scriptType: ScriptType): ByteArray? {
        return when (scriptType) {
            ScriptType.P2PKH -> {
                // OP_DUP OP_HASH160 <20 bytes> OP_EQUALVERIFY OP_CHECKSIG
                scriptPubKey.sliceArray(3 until 23)
            }
            ScriptType.P2WPKH -> {
                // OP_0 <20 bytes>
                scriptPubKey.sliceArray(2 until 22)
            }
            ScriptType.P2SH_P2WPKH,
            ScriptType.P2TR -> null
        }
    }

}
