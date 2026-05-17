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

        // Add/update UTXOs. Confirmation counts aren't stored — they're computed
        // on read from the current chain tip via UnspentOutput.confirmations().
        for (apiUtxo in utxos) {
            val scriptPubKey = addressConverter.addressToScriptPubKey(address)
                ?: continue

            val utxo = UnspentOutput(
                transactionHash = ByteVector32.fromValidHex(apiUtxo.txid),
                outputIndex = apiUtxo.vout,
                value = apiUtxo.value,
                scriptPubKey = scriptPubKey,
                scriptType = scriptType,
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

    /**
     * Hex-keyed lookup tables built lazily from the wallet's keys the first time
     * a lookup is needed in this processor. Each [TransactionProcessor] handles
     * one address, so the indices stay fresh for the lifetime of one
     * [processTransactions] / [processUtxos] call — by the time the next address
     * is synced, the next processor is built and re-reads the key set.
     *
     * Pre-PR-10: every vin/vout lookup either linear-scanned the wallet's keys by
     * pubkey-hash (O(N) per call) or recomputed every key's scriptPubKey to
     * compare bytes (O(N) script derivations per call). For a wallet with K
     * keys processing T txs with V vins+vouts each, the old code did K·T·V
     * derivations per address. The new code does K script derivations *once*
     * for the index and then K-keyed lookups.
     */
    private var walletKeysByPubKeyHash: Map<String, WalletPublicKey>? = null
    private var walletKeysByScriptPubKey: Map<String, WalletPublicKey>? = null

    private suspend fun ensureKeyIndices() {
        if (walletKeysByPubKeyHash != null) return
        val keys = publicKeyManager.getAllPublicKeys()
        walletKeysByPubKeyHash = keys.associateBy { it.publicKeyHash.toHexKey() }
        walletKeysByScriptPubKey = keys.associateBy {
            addressConverter.createScriptPubKey(it.publicKey, it.scriptType).toHexKey()
        }
    }

    private suspend fun findWalletKey(scriptPubKey: ByteArray, scriptType: ScriptType): WalletPublicKey? {
        ensureKeyIndices()
        val pubKeyHash = extractPubKeyHash(scriptPubKey, scriptType)
        if (pubKeyHash != null) {
            // P2PKH and P2WPKH put the hash160 of the pubkey directly in the
            // scriptPubKey, so the index is keyed on that hash.
            walletKeysByPubKeyHash!![pubKeyHash.toHexKey()]?.let { return it }
        }
        // P2SH variants and P2TR don't expose a pubkey hash in their scriptPubKey;
        // fall back to a direct scriptPubKey-bytes match. Also acts as the
        // catch-all for any future script type the extractor doesn't know yet.
        return walletKeysByScriptPubKey!![scriptPubKey.toHexKey()]
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
            // P2SH and its wrapped variants put the hash160 of the redeem script
            // (not the pubkey) into the scriptPubKey; P2TR puts the tweaked
            // x-only pubkey. Neither yields a direct pubkey hash to look up.
            ScriptType.P2SH,
            ScriptType.P2SH_P2WPKH,
            ScriptType.P2TR -> null
        }
    }

    private fun ByteArray.toHexKey(): String = buildString(size * 2) {
        for (byte in this@toHexKey) {
            val v = byte.toInt() and 0xff
            if (v < 0x10) append('0')
            append(v.toString(16))
        }
    }
}
