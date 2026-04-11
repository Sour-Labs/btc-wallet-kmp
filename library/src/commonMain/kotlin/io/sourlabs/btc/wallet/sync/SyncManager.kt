package io.sourlabs.btc.wallet.sync

import io.sourlabs.btc.wallet.core.SyncConfig
import io.sourlabs.btc.wallet.keys.AddressConverter
import io.sourlabs.btc.wallet.keys.PublicKeyManager
import io.sourlabs.btc.wallet.models.*
import io.sourlabs.btc.wallet.storage.BlockInfoStorage
import io.sourlabs.btc.wallet.storage.TransactionStorage
import io.sourlabs.btc.wallet.storage.UnspentOutputStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock

/**
 * Manages wallet synchronization with the blockchain.
 */
class SyncManager(
    private val publicKeyManager: PublicKeyManager,
    private val addressConverter: AddressConverter,
    private val transactionStorage: TransactionStorage,
    private val utxoStorage: UnspentOutputStorage,
    private val blockInfoStorage: BlockInfoStorage,
    private val syncConfigs: List<SyncConfig>
) {
    init {
        require(syncConfigs.isNotEmpty()) { "At least one SyncConfig must be provided" }
    }
    constructor(
        publicKeyManager: PublicKeyManager,
        addressConverter: AddressConverter,
        transactionStorage: TransactionStorage,
        utxoStorage: UnspentOutputStorage,
        blockInfoStorage: BlockInfoStorage,
        syncConfig: SyncConfig
    ) : this(publicKeyManager, addressConverter, transactionStorage, utxoStorage, blockInfoStorage, listOf(syncConfig))

    private val _syncState = MutableStateFlow<SyncState>(SyncState.NotSynced)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _events = MutableSharedFlow<WalletEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WalletEvent> = _events.asSharedFlow()

    private var syncJob: Job? = null
    private var api: BlockchainExplorerApi? = null
    private var activeSyncConfig: SyncConfig = syncConfigs.first()

    private val baseUrl: String
        get() = when (val config = activeSyncConfig) {
            is SyncConfig.MempoolSpace -> config.baseUrl
            is SyncConfig.BlockStream -> config.baseUrl
            is SyncConfig.MyUmbrel -> config.baseUrl
            is SyncConfig.CustomApi -> config.baseUrl
        }

    private val pollingInterval: Long
        get() = when (val config = activeSyncConfig) {
            is SyncConfig.MempoolSpace -> config.pollingIntervalMs
            is SyncConfig.BlockStream -> config.pollingIntervalMs
            is SyncConfig.MyUmbrel -> config.pollingIntervalMs
            is SyncConfig.CustomApi -> config.pollingIntervalMs
        }

    /**
     * Start synchronization. Tries each configured SyncConfig in order
     * until one succeeds. If all fail, reports the last error.
     */
    suspend fun start(scope: CoroutineScope) {
        if (syncJob?.isActive == true) return

        syncJob = scope.launch {
            val lastError = tryStartWithFallbacks()
            if (lastError != null) {
                _syncState.value = SyncState.Error(lastError.message ?: "Sync failed", lastError)
                _events.emit(WalletEvent.SyncStateChanged(_syncState.value))
                _events.emit(WalletEvent.WalletError(lastError.message ?: "Sync failed", lastError))
                return@launch
            }

            // Start periodic polling
            while (isActive) {
                delay(pollingInterval)
                performIncrementalSync()
            }
        }
    }

    /**
     * Try each sync config in order. Returns null on success, or the last exception on total failure.
     */
    private suspend fun tryStartWithFallbacks(): Exception? {
        var lastError: Exception? = null

        for ((index, config) in syncConfigs.withIndex()) {
            activeSyncConfig = config
            api?.close()
            api = BlockchainExplorerApi(baseUrl)

            try {
                performFullSync()
                return null // success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                val hasMoreFallbacks = index < syncConfigs.size - 1
                if (hasMoreFallbacks) {
                    _events.emit(
                        WalletEvent.WalletError(
                            "Sync failed with ${config::class.simpleName}: ${e.message}, trying fallback...",
                            e
                        )
                    )
                }
            }
        }

        return lastError
    }

    /**
     * Stop synchronization.
     */
    fun stop() {
        syncJob?.cancel()
        syncJob = null
        api?.close()
        api = null
    }

    /**
     * Trigger a manual refresh.
     */
    suspend fun refresh() {
        try {
            performFullSync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _syncState.value = SyncState.Error(e.message ?: "Sync failed", e)
            _events.emit(WalletEvent.SyncStateChanged(_syncState.value))
        }
    }

    /**
     * Get recommended fee rates.
     */
    suspend fun getRecommendedFees(): FeeEstimates? {
        return try {
            api?.getRecommendedFees()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Broadcast a transaction.
     * @return transaction ID if successful
     */
    suspend fun broadcastTransaction(rawTxHex: String): Result<String> {
        return try {
            val txId = api?.broadcastTransaction(rawTxHex)
                ?: return Result.failure(Exception("API not initialized"))
            Result.success(txId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get current block height.
     */
    suspend fun getCurrentBlockHeight(): Int? {
        return try {
            api?.getBlockHeight()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun performFullSync() {
        val currentApi = api ?: throw IllegalStateException("API not initialized")

        _syncState.value = SyncState.Syncing(0.0, "Starting sync...")
        _events.emit(WalletEvent.SyncStateChanged(_syncState.value))

        // Get current block height
        val blockHeight = currentApi.getBlockHeight()
        val blockHash = currentApi.getBlockHash(blockHeight)
        val block = currentApi.getBlock(blockHash)

        // Update block info
        val blockInfo = BlockInfo(
            height = blockHeight,
            hash = blockHash,
            timestamp = block.timestamp
        )
        blockInfoStorage.saveBlockInfo(blockInfo)
        _events.emit(WalletEvent.NewBlock(blockHeight, blockHash, block.timestamp))

        // Get all addresses to sync
        val externalKeys = publicKeyManager.getExternalPublicKeys()
        val internalKeys = publicKeyManager.getInternalPublicKeys()
        val allKeys = externalKeys + internalKeys

        val totalAddresses = allKeys.size
        var processed = 0

        // Sync each address
        for (key in allKeys) {
            val address = addressConverter.toAddress(key)

            _syncState.value = SyncState.Syncing(
                progress = processed.toDouble() / totalAddresses,
                description = "Syncing address ${processed + 1}/$totalAddresses"
            )

            try {
                // Get transactions
                val transactions = currentApi.getAddressTransactions(address)
                if (transactions.isNotEmpty()) {
                    // Mark address as used
                    publicKeyManager.markAsUsed(key.path)

                    // Process transactions
                    val processor = TransactionProcessor(
                        publicKeyManager,
                        transactionStorage,
                        utxoStorage,
                        addressConverter
                    )
                    val newTxs = processor.processTransactions(address, transactions, blockHeight)
                    for (tx in newTxs) {
                        if (tx.type == TransactionType.INCOMING) {
                            _events.emit(WalletEvent.TransactionReceived(tx))
                        }
                    }
                }

                // Get UTXOs
                val utxos = currentApi.getAddressUtxos(address)
                val processor = TransactionProcessor(
                    publicKeyManager,
                    transactionStorage,
                    utxoStorage,
                    addressConverter
                )
                processor.processUtxos(address, utxos, key.path, key.scriptType, blockHeight)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Continue with other addresses on error
            }

            processed++
        }

        val syncTime = Clock.System.now().toEpochMilliseconds()
        _syncState.value = SyncState.Synced(syncTime)
        _events.emit(WalletEvent.SyncStateChanged(_syncState.value))

        // Emit balance update
        val balance = calculateBalance()
        _events.emit(WalletEvent.BalanceUpdated(balance))
    }

    private suspend fun performIncrementalSync() {
        val currentApi = api ?: return

        try {
            // Check for new blocks
            val currentHeight = currentApi.getBlockHeight()
            val lastBlock = blockInfoStorage.getLastBlockInfo()

            if (lastBlock == null || currentHeight > lastBlock.height) {
                // New block detected, do full sync
                performFullSync()
            } else {
                // Just check unconfirmed transactions
                checkUnconfirmedTransactions(currentHeight)

                // Restore synced state if we recovered from a transient error
                if (_syncState.value is SyncState.Error) {
                    val syncTime = Clock.System.now().toEpochMilliseconds()
                    _syncState.value = SyncState.Synced(syncTime)
                    _events.emit(WalletEvent.SyncStateChanged(_syncState.value))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _syncState.value = SyncState.Error(e.message ?: "Sync failed", e)
            _events.emit(WalletEvent.SyncStateChanged(_syncState.value))
            _events.emit(WalletEvent.WalletError(e.message ?: "Sync failed", e))
        }
    }

    private suspend fun checkUnconfirmedTransactions(currentHeight: Int) {
        val unconfirmed = transactionStorage.getUnconfirmedTransactions()

        for (tx in unconfirmed) {
            try {
                val apiTx = api?.getTransaction(tx.txId) ?: continue

                if (apiTx.status.confirmed && apiTx.status.blockHeight != null) {
                    transactionStorage.updateConfirmation(
                        tx.txId,
                        apiTx.status.blockHeight,
                        apiTx.status.blockTime ?: 0
                    )

                    _events.emit(
                        WalletEvent.TransactionStatusChanged(
                            tx.txId,
                            tx.status,
                            TransactionStatus.CONFIRMED
                        )
                    )
                }
            } catch (_: Exception) {
                // Continue checking other transactions
            }
        }
    }

    private suspend fun calculateBalance(): BalanceInfo {
        val utxos = utxoStorage.getAllUtxos()

        var spendable = 0L
        var unconfirmed = 0L
        var locked = 0L

        for (utxo in utxos) {
            when {
                !utxo.isSpendable -> locked += utxo.value
                utxo.confirmations < 1 -> unconfirmed += utxo.value
                else -> spendable += utxo.value
            }
        }

        return BalanceInfo(spendable, unconfirmed, locked)
    }
}
