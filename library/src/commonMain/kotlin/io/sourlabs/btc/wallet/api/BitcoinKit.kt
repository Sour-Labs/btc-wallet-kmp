package io.sourlabs.btc.wallet.api

import co.touchlab.kermit.Logger
import io.sourlabs.btc.wallet.core.SyncConfig
import io.sourlabs.btc.wallet.core.WalletConfig
import io.sourlabs.btc.wallet.descriptors.OutputDescriptor
import io.sourlabs.btc.wallet.keys.AddressConverter
import io.sourlabs.btc.wallet.keys.HDWalletManager
import io.sourlabs.btc.wallet.keys.HdWalletKeySource
import io.sourlabs.btc.wallet.keys.MultisigKeySource
import io.sourlabs.btc.wallet.keys.PublicKeyManager
import io.sourlabs.btc.wallet.keys.WalletKeySource
import io.sourlabs.btc.wallet.models.*
import io.sourlabs.btc.wallet.storage.InMemoryWalletStorage
import io.sourlabs.btc.wallet.storage.WalletStorage
import io.sourlabs.btc.wallet.sync.FeeEstimates
import io.sourlabs.btc.wallet.sync.BlockchainExplorerApi
import io.sourlabs.btc.wallet.sync.MultiPurposeScanner
import io.sourlabs.btc.wallet.sync.SyncManager
import io.sourlabs.btc.wallet.sync.WalletScanResult
import io.sourlabs.btc.wallet.transactions.CreatedTransaction
import io.sourlabs.btc.wallet.transactions.SendInfo
import io.sourlabs.btc.wallet.transactions.TransactionCreator
import io.sourlabs.btc.wallet.utxo.SelectionStrategy
import io.sourlabs.btc.wallet.utxo.UnspentOutputProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

private val log = Logger.withTag("BitcoinKit")

/**
 * Main facade for the Bitcoin wallet library.
 * Provides a high-level API for wallet management.
 */
class BitcoinKit private constructor(
    private val hdWalletManager: HDWalletManager,
    private val publicKeyManager: PublicKeyManager,
    private val addressConverter: AddressConverter,
    private val utxoProvider: UnspentOutputProvider,
    private val transactionCreator: TransactionCreator,
    private val syncManager: SyncManager,
    private val storage: WalletStorage,
    private val scope: CoroutineScope
) {
    /**
     * The network this wallet is configured for.
     */
    val network: Network = hdWalletManager.network

    /**
     * The BIP purpose (address type) this wallet uses.
     */
    val purpose: Purpose = hdWalletManager.purpose

    /**
     * Whether this is a watch-only wallet (no signing capability).
     */
    val isWatchOnly: Boolean = hdWalletManager.isWatchOnly

    /**
     * Current synchronization state.
     */
    val syncState: StateFlow<SyncState> = syncManager.syncState

    /**
     * Wallet events stream.
     */
    val events: SharedFlow<WalletEvent> = syncManager.events

    // Lifecycle

    /**
     * Start the wallet (initialize keys and begin syncing).
     *
     * Suspends until the first sync attempt has finished — `syncState` is
     * either [SyncState.Synced] or [SyncState.Error] by the time `start()`
     * returns. This lets callers immediately use [getBalance], [transactions],
     * or [receiveAddress] without manually observing the sync state flow.
     *
     * For [SyncMode.OneShot] / [SyncMode.Continuous] the await covers the
     * full initial scan (and any fallback attempts) before yielding. For
     * [SyncMode.IncrementalOnly] the kit flips to Synced immediately, so
     * `start()` returns almost instantly.
     *
     * See [SyncMode] for per-mode behavior and preconditions. Defaults to
     * [SyncMode.Continuous] (full sync then polling) for source compatibility
     * with earlier versions.
     */
    suspend fun start(mode: SyncMode = SyncMode.Continuous) {
        log.i { "start(mode=$mode, network=$network, purpose=$purpose, watchOnly=$isWatchOnly)" }
        publicKeyManager.initialize()
        syncManager.start(scope, mode)
        // Wait for the launched sync coroutine to reach a terminal state.
        // syncState starts at NotSynced and transitions through Syncing to
        // Synced or Error; we wait for the first terminal emission.
        syncState.first { it is SyncState.Synced || it is SyncState.Error }
    }

    /**
     * Stop the wallet (stop syncing). Suspends until the sync coroutine has
     * actually exited.
     */
    suspend fun stop() {
        log.i { "stop()" }
        syncManager.stop()
    }

    /**
     * Trigger a manual refresh/sync.
     */
    suspend fun refresh() {
        log.i { "refresh()" }
        syncManager.refresh()
    }

    // Addresses

    /**
     * Get a fresh receive address.
     */
    suspend fun receiveAddress(): String {
        val key = publicKeyManager.getReceivePublicKey()
        return addressConverter.toAddress(key)
    }

    /**
     * Get the wallet's first receive address (external index 0).
     *
     * Unlike [receiveAddress], this is a stable identifier: it always returns
     * the address at receive index 0 and never advances as addresses are used.
     */
    suspend fun firstReceiveAddress(): String {
        val key = publicKeyManager.externalKeyAt(0)
        return addressConverter.toAddress(key)
    }

    /**
     * Get all used receive addresses.
     */
    suspend fun usedAddresses(): List<String> {
        return publicKeyManager.getExternalPublicKeys()
            .filter { it.isUsed }
            .map { addressConverter.toAddress(it) }
    }

    /**
     * Validate if an address is valid for this network.
     */
    fun validateAddress(address: String): Boolean {
        return addressConverter.isValidAddress(address)
    }

    /**
     * Parse an address and get its information.
     */
    fun parseAddress(address: String): AddressConverter.AddressInfo? {
        return addressConverter.parseAddress(address)
    }

    // Balance

    /**
     * Get the current balance.
     */
    suspend fun getBalance(): BalanceInfo {
        return utxoProvider.getBalance()
    }

    /**
     * Get the maximum spendable amount after fees.
     */
    suspend fun maximumSpendableValue(feeRate: Long): Long {
        return utxoProvider.getMaximumSpendable(feeRate)
    }

    // Transactions

    /**
     * Get wallet transactions.
     * @param type optional filter by transaction type
     * @param limit maximum number of transactions to return
     */
    suspend fun transactions(
        type: TransactionType? = null,
        limit: Int? = null
    ): List<WalletTransaction> {
        return storage.transactionStorage.getTransactions(type, limit)
    }

    /**
     * Get a specific transaction by ID.
     */
    suspend fun getTransaction(txId: String): WalletTransaction? {
        return storage.transactionStorage.getTransaction(txId)
    }

    // Sending

    /**
     * Get information about a potential send without creating the transaction.
     * Pass the same [subtractFeeFromAmount] you'd pass to [createTransaction] /
     * [send] so the reported amounts match what would actually be built.
     */
    suspend fun sendInfo(
        toAddress: String,
        amount: Long,
        feeRate: Long,
        strategy: SelectionStrategy = SelectionStrategy.AUTOMATIC,
        subtractFeeFromAmount: Boolean = false
    ): SendInfo? {
        return transactionCreator.getSendInfo(toAddress, amount, feeRate, strategy, subtractFeeFromAmount)
    }

    /**
     * Create and sign a transaction.
     * @return the created transaction (not yet broadcast)
     */
    suspend fun createTransaction(
        toAddress: String,
        amount: Long,
        feeRate: Long,
        strategy: SelectionStrategy = SelectionStrategy.AUTOMATIC,
        rbfEnabled: Boolean = true,
        subtractFeeFromAmount: Boolean = false
    ): CreatedTransaction {
        return transactionCreator.create(
            toAddress = toAddress,
            amount = amount,
            feeRate = feeRate,
            strategy = strategy,
            rbfEnabled = rbfEnabled,
            subtractFeeFromAmount = subtractFeeFromAmount
        )
    }

    /**
     * Create, sign, and broadcast a transaction.
     * @return the transaction ID if successful
     */
    suspend fun send(
        toAddress: String,
        amount: Long,
        feeRate: Long,
        strategy: SelectionStrategy = SelectionStrategy.AUTOMATIC,
        rbfEnabled: Boolean = true,
        subtractFeeFromAmount: Boolean = false
    ): Result<String> {
        log.i {
            "send(amount=$amount sat, feeRate=$feeRate sat/vB, strategy=$strategy, " +
                "rbf=$rbfEnabled, subtractFee=$subtractFeeFromAmount)"
        }
        val createdTx = createTransaction(
            toAddress = toAddress,
            amount = amount,
            feeRate = feeRate,
            strategy = strategy,
            rbfEnabled = rbfEnabled,
            subtractFeeFromAmount = subtractFeeFromAmount
        )

        return broadcastTransaction(createdTx.rawTx.toHexString())
    }

    /**
     * Broadcast a raw transaction.
     */
    suspend fun broadcastTransaction(rawTxHex: String): Result<String> {
        return syncManager.broadcastTransaction(rawTxHex)
    }

    /**
     * Create a sweep transaction (send all funds).
     */
    suspend fun createSweepTransaction(
        toAddress: String,
        feeRate: Long,
        rbfEnabled: Boolean = true
    ): CreatedTransaction {
        return transactionCreator.createSweep(toAddress, feeRate, rbfEnabled)
    }

    // UTXOs

    /**
     * Get all unspent outputs.
     */
    suspend fun getUnspentOutputs(): List<UnspentOutput> {
        return utxoProvider.getAllUtxos()
    }

    /**
     * Get spendable unspent outputs.
     */
    suspend fun getSpendableOutputs(): List<UnspentOutput> {
        return utxoProvider.getSpendableUtxos()
    }

    // Fee Estimation

    /**
     * Get recommended fee rates.
     */
    suspend fun getRecommendedFees(): FeeEstimates? {
        return syncManager.getRecommendedFees()
    }

    // Block Info

    /**
     * Get current block height.
     */
    suspend fun getCurrentBlockHeight(): Int? {
        return syncManager.getCurrentBlockHeight()
    }

    /**
     * Get last synced block info.
     */
    suspend fun getLastBlockInfo(): BlockInfo? {
        return storage.blockInfoStorage.getLastBlockInfo()
    }

    // Cleanup

    /**
     * Clear all wallet data.
     */
    suspend fun clearData() {
        log.i { "clearData()" }
        stop()
        storage.clearAll()
    }

    private fun ByteArray.toHexString(): String = joinToString("") {
        val hex = (it.toInt() and 0xFF).toString(16)
        if (hex.length == 1) "0$hex" else hex
    }

    /**
     * Builder for creating BitcoinKit instances.
     */
    class Builder(private val walletConfig: WalletConfig) {
        // Null until either the caller sets one via syncConfig(...) or build()
        // falls back to the default. Deferred so that REGTEST callers — for
        // whom there is no public default endpoint — can supply their own
        // SyncConfig.CustomApi(...) before build() is invoked, instead of the
        // Builder constructor throwing eagerly.
        private var syncConfig: SyncConfig? = null
        private var fallbackSyncConfigs: MutableList<SyncConfig> = mutableListOf()
        private var storage: WalletStorage = InMemoryWalletStorage()
        private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Set the primary sync configuration.
         */
        fun syncConfig(config: SyncConfig): Builder {
            this.syncConfig = config
            return this
        }

        /**
         * Add a fallback sync configuration. If the primary (or earlier fallbacks)
         * fail, these will be tried in the order they were added.
         */
        fun addFallbackSyncConfig(config: SyncConfig): Builder {
            this.fallbackSyncConfigs.add(config)
            return this
        }

        /**
         * Set custom storage implementation.
         */
        fun storage(storage: WalletStorage): Builder {
            this.storage = storage
            return this
        }

        /**
         * Set custom coroutine scope.
         */
        fun scope(scope: CoroutineScope): Builder {
            this.scope = scope
            return this
        }

        private fun buildKeySource(
            config: WalletConfig,
            hdWalletManager: HDWalletManager,
        ): WalletKeySource = when (config) {
            is WalletConfig.WatchOnlyDescriptor -> when (val parsed = config.parsedOutputDescriptor) {
                is OutputDescriptor.SingleKey -> HdWalletKeySource(hdWalletManager)
                is OutputDescriptor.Multisig -> MultisigKeySource(parsed.descriptor)
            }
            // Single-key non-descriptor configs (mnemonic / seed / xprv / xpub)
            // all use the standard HD derivation strategy.
            is WalletConfig.FromMnemonic,
            is WalletConfig.FromSeed,
            is WalletConfig.FromExtendedPrivateKey,
            is WalletConfig.WatchOnly -> HdWalletKeySource(hdWalletManager)
        }

        /**
         * Build the BitcoinKit instance.
         */
        fun build(): BitcoinKit {
            val hdWalletManager = HDWalletManager.fromConfig(walletConfig)
            val keySource: WalletKeySource = buildKeySource(walletConfig, hdWalletManager)
            val addressConverter = AddressConverter(walletConfig.network)
            val publicKeyManager = PublicKeyManager(
                keySource = keySource,
                storage = storage.publicKeyStorage,
                gapLimit = walletConfig.gapLimit
            )
            val utxoProvider = UnspentOutputProvider(
                storage = storage.unspentOutputStorage,
                blockInfoStorage = storage.blockInfoStorage,
                confirmationsThreshold = walletConfig.confirmationsThreshold
            )
            val transactionCreator = TransactionCreator(
                hdWalletManager = hdWalletManager,
                publicKeyManager = publicKeyManager,
                utxoProvider = utxoProvider,
                addressConverter = addressConverter,
                transactionStorage = storage.transactionStorage,
                unspentOutputStorage = storage.unspentOutputStorage,
            )
            val primarySyncConfig = syncConfig ?: SyncConfig.defaultForNetwork(walletConfig.network)
            val allSyncConfigs = listOf(primarySyncConfig) + fallbackSyncConfigs
            val syncManager = SyncManager(
                publicKeyManager = publicKeyManager,
                addressConverter = addressConverter,
                transactionStorage = storage.transactionStorage,
                utxoStorage = storage.unspentOutputStorage,
                blockInfoStorage = storage.blockInfoStorage,
                syncConfigs = allSyncConfigs
            )

            return BitcoinKit(
                hdWalletManager = hdWalletManager,
                publicKeyManager = publicKeyManager,
                addressConverter = addressConverter,
                utxoProvider = utxoProvider,
                transactionCreator = transactionCreator,
                syncManager = syncManager,
                storage = storage,
                scope = scope
            )
        }
    }

    companion object {
        /**
         * Create a builder for BitcoinKit.
         */
        fun builder(config: WalletConfig): Builder = Builder(config)

        /**
         * Scan for existing wallet activity across all address types. Useful
         * during wallet restoration to discover funds.
         *
         * Accepts any [SyncConfig] — defaults to the public Blockstream endpoint
         * for the given network when [syncConfig] is null. Pass a
         * [SyncConfig.MempoolSpace], [SyncConfig.CustomApi], or an authenticated
         * Blockstream Enterprise config when those suit better.
         */
        suspend fun scanWallet(
            mnemonic: List<String>,
            passphrase: String = "",
            network: Network = Network.MAINNET,
            syncConfig: SyncConfig? = null,
        ): WalletScanResult {
            log.i { "scanWallet(network=$network) started" }
            val seed = fr.acinq.bitcoin.MnemonicCode.toSeed(mnemonic, passphrase)
            val effectiveSyncConfig = syncConfig ?: SyncConfig.defaultForNetwork(network)
            val api = when (effectiveSyncConfig) {
                is SyncConfig.BlockStream -> BlockchainExplorerApi(effectiveSyncConfig)
                is SyncConfig.MempoolSpace -> BlockchainExplorerApi(effectiveSyncConfig.baseUrl)
                is SyncConfig.CustomApi -> BlockchainExplorerApi(effectiveSyncConfig.baseUrl)
            }

            return try {
                val scanner = MultiPurposeScanner(seed, network, api)
                val result = scanner.scanAll()
                log.i {
                    "scanWallet completed: totalBalance=${result.totalBalance} sat, " +
                        "activePurposes=${result.activePurposes}, recommended=${result.recommendedPurpose}"
                }
                result
            } finally {
                api.close()
            }
        }
    }
}
