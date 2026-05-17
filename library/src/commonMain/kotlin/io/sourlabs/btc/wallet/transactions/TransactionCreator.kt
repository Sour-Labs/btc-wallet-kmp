package io.sourlabs.btc.wallet.transactions

import fr.acinq.bitcoin.Transaction
import io.sourlabs.btc.wallet.keys.AddressConverter
import io.sourlabs.btc.wallet.keys.HDWalletManager
import io.sourlabs.btc.wallet.keys.PublicKeyManager
import io.sourlabs.btc.wallet.models.TransactionStatus
import io.sourlabs.btc.wallet.models.TransactionType
import io.sourlabs.btc.wallet.models.UnspentOutput
import io.sourlabs.btc.wallet.models.WalletPublicKey
import io.sourlabs.btc.wallet.models.WalletTransaction
import io.sourlabs.btc.wallet.storage.TransactionStorage
import io.sourlabs.btc.wallet.storage.UnspentOutputStorage
import io.sourlabs.btc.wallet.utxo.SelectionStrategy
import io.sourlabs.btc.wallet.utxo.UnspentOutputProvider
import io.sourlabs.btc.wallet.utxo.UnspentOutputSelector

/**
 * Information about a transaction to be created.
 */
data class SendInfo(
    /**
     * Amount to be sent.
     */
    val amount: Long,

    /**
     * Fee amount.
     */
    val fee: Long,

    /**
     * Change amount (if any).
     */
    val change: Long,

    /**
     * Number of inputs.
     */
    val inputCount: Int,

    /**
     * Total input value.
     */
    val totalInput: Long,

    /**
     * Whether there will be a change output.
     */
    val hasChange: Boolean
)

/**
 * Result of creating and signing a transaction.
 */
data class CreatedTransaction(
    /**
     * The signed transaction.
     */
    val transaction: Transaction,

    /**
     * Transaction ID (hash).
     */
    val txId: String,

    /**
     * Raw transaction bytes.
     */
    val rawTx: ByteArray,

    /**
     * Fee paid.
     */
    val fee: Long,

    /**
     * Virtual size in vBytes.
     */
    val vSize: Int
) {
    /**
     * Effective fee rate in sat/vB.
     */
    val feeRate: Double
        get() = fee.toDouble() / vSize

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as CreatedTransaction

        if (transaction != other.transaction) return false
        if (txId != other.txId) return false
        if (!rawTx.contentEquals(other.rawTx)) return false
        if (fee != other.fee) return false
        if (vSize != other.vSize) return false

        return true
    }

    override fun hashCode(): Int {
        var result = transaction.hashCode()
        result = 31 * result + txId.hashCode()
        result = 31 * result + rawTx.contentHashCode()
        result = 31 * result + fee.hashCode()
        result = 31 * result + vSize
        return result
    }
}

/**
 * Orchestrates transaction creation: UTXO selection, building, and signing.
 */
class TransactionCreator(
    private val hdWalletManager: HDWalletManager,
    private val publicKeyManager: PublicKeyManager,
    private val utxoProvider: UnspentOutputProvider,
    private val addressConverter: AddressConverter,
    private val transactionStorage: TransactionStorage,
    private val unspentOutputStorage: UnspentOutputStorage,
) {
    private val selector = UnspentOutputSelector()
    private val builder = TransactionBuilder(addressConverter)
    private val signer: TransactionSigner? = if (!hdWalletManager.isWatchOnly) {
        TransactionSigner(hdWalletManager)
    } else {
        null
    }

    /**
     * Reserve the spent UTXOs locally, mark input/change keys as used, and persist a
     * `PENDING` [WalletTransaction] so the wallet immediately reflects the just-built
     * transaction. Called from each `create*` path after a successful sign, so a
     * subsequent `create*` call in the same process doesn't re-select the same UTXOs
     * or reuse the same change address.
     *
     * The [changeKey] must be the exact key the builder allocated for this tx's
     * change output (or null for a sweep / no-change build). Don't re-derive it from
     * the unused-key pool here — marking input keys used between build time and now
     * could shift "lowest unused internal key" away from the one the builder picked,
     * and the cost of getting that wrong is silently marking the wrong key used.
     *
     * If the caller never actually broadcasts the resulting transaction, the next
     * full sync will reconcile by re-adding the unspent outputs and the `PENDING`
     * entry stays dangling until explicitly cleared. That's the documented trade-off
     * for keeping `createTransaction()` side-effecting; see PR-02 of the OSS readiness
     * audit for the rationale.
     */
    private suspend fun recordOutgoingTransaction(
        unsignedTx: UnsignedTransaction,
        signedTx: Transaction,
        fee: Long,
        changeKey: WalletPublicKey?,
    ) {
        for (key in unsignedTx.publicKeys.distinctBy { it.path }) {
            publicKeyManager.markAsUsed(key.path)
        }
        changeKey?.let { publicKeyManager.markAsUsed(it.path) }
        unspentOutputStorage.deleteUtxos(unsignedTx.utxos.map { it.id })

        val inputAmount = unsignedTx.utxos.sumOf { it.value }
        val outputAmount = if (unsignedTx.hasChange && unsignedTx.changeOutputIndex != null) {
            signedTx.txOut[unsignedTx.changeOutputIndex].amount.toLong()
        } else {
            0L
        }
        val netAmount = outputAmount - inputAmount

        transactionStorage.saveTransaction(
            WalletTransaction(
                txId = signedTx.txid.toString(),
                transaction = signedTx,
                blockHeight = null,
                timestamp = null,
                status = TransactionStatus.PENDING,
                type = TransactionType.OUTGOING,
                amount = netAmount,
                fee = fee,
            )
        )
    }

    /**
     * Get information about a potential send without creating the transaction.
     */
    suspend fun getSendInfo(
        toAddress: String,
        amount: Long,
        feeRate: Long,
        strategy: SelectionStrategy = SelectionStrategy.AUTOMATIC
    ): SendInfo? {
        // Validate address
        if (!addressConverter.isValidAddress(toAddress)) {
            throw IllegalArgumentException("Invalid address: $toAddress")
        }

        val spendableUtxos = utxoProvider.getSpendableUtxos()
        val selection = selector.select(spendableUtxos, amount, feeRate, strategy) ?: return null

        return SendInfo(
            amount = amount,
            fee = selection.fee,
            change = selection.change,
            inputCount = selection.selectedUtxos.size,
            totalInput = selection.totalInput,
            hasChange = selection.hasChange
        )
    }

    /**
     * Create and sign a transaction.
     *
     * @param toAddress destination address
     * @param amount amount to send in satoshis
     * @param feeRate fee rate in sat/vB
     * @param strategy UTXO selection strategy
     * @param rbfEnabled whether to enable Replace-By-Fee
     * @param subtractFeeFromAmount whether to subtract fee from the send amount
     */
    suspend fun create(
        toAddress: String,
        amount: Long,
        feeRate: Long,
        strategy: SelectionStrategy = SelectionStrategy.AUTOMATIC,
        rbfEnabled: Boolean = true,
        subtractFeeFromAmount: Boolean = false
    ): CreatedTransaction {
        // Validate address
        if (!addressConverter.isValidAddress(toAddress)) {
            throw IllegalArgumentException("Invalid address: $toAddress")
        }

        require(signer != null) { "Cannot create signed transactions with a watch-only wallet" }

        // Get spendable UTXOs
        val spendableUtxos = utxoProvider.getSpendableUtxos()

        // Select UTXOs
        val selection = selector.select(spendableUtxos, amount, feeRate, strategy)
            ?: throw InsufficientFundsException("Insufficient funds to send $amount satoshis with fee rate $feeRate sat/vB")

        // Get public keys for inputs
        val inputKeys = selection.selectedUtxos.map { utxo ->
            publicKeyManager.findByPath(utxo.publicKeyPath)
                ?: throw IllegalStateException("Public key not found for UTXO: ${utxo.id}")
        }

        // Get change key if needed
        val changeKey = if (selection.hasChange) {
            publicKeyManager.getChangePublicKey()
        } else {
            null
        }

        // Build transaction params
        val params = TransactionParams(
            toAddress = toAddress,
            amount = amount,
            feeRate = feeRate,
            rbfEnabled = rbfEnabled,
            subtractFeeFromAmount = subtractFeeFromAmount
        )

        // Build unsigned transaction
        val unsignedTx = builder.build(selection, params, changeKey, inputKeys)

        // Sign transaction
        val signedTx = signer.sign(unsignedTx)

        // Reserve UTXOs, mark keys used, and persist PENDING tx so a subsequent
        // create*() call in the same process doesn't re-select the same UTXOs
        // or hand out the same change address.
        recordOutgoingTransaction(unsignedTx, signedTx, unsignedTx.fee, changeKey)

        // Serialize
        val rawTx = Transaction.write(signedTx)

        return CreatedTransaction(
            transaction = signedTx,
            txId = signedTx.txid.toString(),
            rawTx = rawTx,
            fee = unsignedTx.fee,
            vSize = (signedTx.weight() + 3) / 4
        )
    }

    /**
     * Create a transaction using manually selected UTXOs.
     */
    suspend fun createWithUtxos(
        toAddress: String,
        amount: Long,
        feeRate: Long,
        utxos: List<UnspentOutput>,
        rbfEnabled: Boolean = true,
        subtractFeeFromAmount: Boolean = false
    ): CreatedTransaction {
        // Validate address
        if (!addressConverter.isValidAddress(toAddress)) {
            throw IllegalArgumentException("Invalid address: $toAddress")
        }

        require(signer != null) { "Cannot create signed transactions with a watch-only wallet" }

        // Select UTXOs (manual)
        val selection = selector.selectManual(utxos, amount, feeRate)
            ?: throw InsufficientFundsException("Selected UTXOs insufficient for amount $amount with fee rate $feeRate")

        // Get public keys for inputs
        val inputKeys = utxos.map { utxo ->
            publicKeyManager.findByPath(utxo.publicKeyPath)
                ?: throw IllegalStateException("Public key not found for UTXO: ${utxo.id}")
        }

        // Get change key if needed
        val changeKey = if (selection.hasChange) {
            publicKeyManager.getChangePublicKey()
        } else {
            null
        }

        // Build transaction params
        val params = TransactionParams(
            toAddress = toAddress,
            amount = amount,
            feeRate = feeRate,
            rbfEnabled = rbfEnabled,
            subtractFeeFromAmount = subtractFeeFromAmount
        )

        // Build unsigned transaction
        val unsignedTx = builder.build(selection, params, changeKey, inputKeys)

        // Sign transaction
        val signedTx = signer.sign(unsignedTx)

        recordOutgoingTransaction(unsignedTx, signedTx, unsignedTx.fee, changeKey)

        // Serialize
        val rawTx = Transaction.write(signedTx)

        return CreatedTransaction(
            transaction = signedTx,
            txId = signedTx.txid.toString(),
            rawTx = rawTx,
            fee = unsignedTx.fee,
            vSize = (signedTx.weight() + 3) / 4
        )
    }

    /**
     * Create a sweep transaction sending all funds to an address.
     */
    suspend fun createSweep(
        toAddress: String,
        feeRate: Long,
        rbfEnabled: Boolean = true
    ): CreatedTransaction {
        // Validate address
        if (!addressConverter.isValidAddress(toAddress)) {
            throw IllegalArgumentException("Invalid address: $toAddress")
        }

        require(signer != null) { "Cannot create signed transactions with a watch-only wallet" }

        // Get all spendable UTXOs
        val utxos = utxoProvider.getSpendableUtxos()
        if (utxos.isEmpty()) {
            throw InsufficientFundsException("No spendable UTXOs available")
        }

        // Get public keys for inputs
        val inputKeys = utxos.map { utxo ->
            publicKeyManager.findByPath(utxo.publicKeyPath)
                ?: throw IllegalStateException("Public key not found for UTXO: ${utxo.id}")
        }

        // Build sweep transaction
        val unsignedTx = builder.buildSweep(utxos, toAddress, feeRate, inputKeys, rbfEnabled)

        // Sign transaction
        val signedTx = signer.sign(unsignedTx)

        // Sweep has no change output, hence no change key to mark used.
        recordOutgoingTransaction(unsignedTx, signedTx, unsignedTx.fee, changeKey = null)

        // Serialize
        val rawTx = Transaction.write(signedTx)

        return CreatedTransaction(
            transaction = signedTx,
            txId = signedTx.txid.toString(),
            rawTx = rawTx,
            fee = unsignedTx.fee,
            vSize = (signedTx.weight() + 3) / 4
        )
    }

    /**
     * Build an unsigned transaction (for watch-only wallets or external signing).
     */
    suspend fun buildUnsigned(
        toAddress: String,
        amount: Long,
        feeRate: Long,
        strategy: SelectionStrategy = SelectionStrategy.AUTOMATIC,
        rbfEnabled: Boolean = true,
        subtractFeeFromAmount: Boolean = false
    ): UnsignedTransaction {
        // Validate address
        if (!addressConverter.isValidAddress(toAddress)) {
            throw IllegalArgumentException("Invalid address: $toAddress")
        }

        // Get spendable UTXOs
        val spendableUtxos = utxoProvider.getSpendableUtxos()

        // Select UTXOs
        val selection = selector.select(spendableUtxos, amount, feeRate, strategy)
            ?: throw InsufficientFundsException("Insufficient funds")

        // Get public keys for inputs
        val inputKeys = selection.selectedUtxos.map { utxo ->
            publicKeyManager.findByPath(utxo.publicKeyPath)
                ?: throw IllegalStateException("Public key not found for UTXO: ${utxo.id}")
        }

        // Get change key if needed
        val changeKey = if (selection.hasChange) {
            publicKeyManager.getChangePublicKey()
        } else {
            null
        }

        // Build transaction params
        val params = TransactionParams(
            toAddress = toAddress,
            amount = amount,
            feeRate = feeRate,
            rbfEnabled = rbfEnabled,
            subtractFeeFromAmount = subtractFeeFromAmount
        )

        return builder.build(selection, params, changeKey, inputKeys)
    }
}

/**
 * Exception thrown when there are insufficient funds for a transaction.
 */
class InsufficientFundsException(message: String) : Exception(message)
