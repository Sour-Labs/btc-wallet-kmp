package io.sourlabs.btc.wallet.sync

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Transaction
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

        // Parse raw transaction
        val rawTxHex = try {
            // We'd need to fetch raw tx, for now create from API data
            Transaction(
                version = apiTx.version.toLong(),
                txIn = emptyList(), // Simplified
                txOut = emptyList(), // Simplified
                lockTime = apiTx.locktime
            )
        } catch (_: Exception) {
            Transaction(version = 2, txIn = emptyList(), txOut = emptyList(), lockTime = 0)
        }

        return WalletTransaction(
            txId = apiTx.txid,
            transaction = rawTxHex,
            blockHeight = apiTx.status.blockHeight,
            timestamp = apiTx.status.blockTime,
            status = status,
            type = type,
            amount = netAmount,
            fee = if (type != TransactionType.INCOMING) apiTx.fee else null
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

    /**
     * Mark addresses as used based on transaction history.
     */
    suspend fun markAddressesUsed(transactions: List<ApiTransaction>) {
        val usedHashes = mutableListOf<ByteArray>()
        val usedPaths = mutableSetOf<String>()

        for (tx in transactions) {
            // Check outputs
            for (vout in tx.vout) {
                val address = vout.scriptPubKeyAddress ?: continue
                val addressInfo = addressConverter.parseAddress(address) ?: continue
                val key = findWalletKey(addressInfo.scriptPubKey, addressInfo.scriptType) ?: continue
                if (usedPaths.add(key.path)) {
                    usedHashes.add(key.publicKeyHash)
                }
            }

            // Check inputs
            for (vin in tx.vin) {
                val prevAddress = vin.prevout?.scriptPubKeyAddress ?: continue
                val addressInfo = addressConverter.parseAddress(prevAddress) ?: continue
                val key = findWalletKey(addressInfo.scriptPubKey, addressInfo.scriptType) ?: continue
                if (usedPaths.add(key.path)) {
                    usedHashes.add(key.publicKeyHash)
                }
            }
        }

        if (usedHashes.isNotEmpty()) {
            publicKeyManager.markAsUsedByHashes(usedHashes)
        }
    }
}
