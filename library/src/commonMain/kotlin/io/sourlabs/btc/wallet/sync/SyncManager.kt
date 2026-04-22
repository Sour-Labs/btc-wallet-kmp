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

// Esplora returns up to 25 confirmed txs per /address/{addr}/txs/chain page.
private const val CHAIN_TXS_PAGE_SIZE = 25
// Hard cap on pagination loops, protecting against pathological cases (e.g. a
// stored cursor whose tx was orphaned by a deep reorg). 200 pages = 5,000 txs.
private const val MAX_CHAIN_PAGES = 200

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

    private fun buildApi(): BlockchainExplorerApi = when (val config = activeSyncConfig) {
        is SyncConfig.BlockStream -> BlockchainExplorerApi(config)
        else -> BlockchainExplorerApi(baseUrl)
    }

    private val pollingInterval: Long
        get() = when (val config = activeSyncConfig) {
            is SyncConfig.MempoolSpace -> config.pollingIntervalMs
            is SyncConfig.BlockStream -> config.pollingIntervalMs
            is SyncConfig.MyUmbrel -> config.pollingIntervalMs
            is SyncConfig.CustomApi -> config.pollingIntervalMs
        }

    /**
     * Start synchronization.
     *
     * The [mode] controls whether a full sync runs, whether polling runs, or both.
     * See [SyncMode] for per-mode semantics and preconditions.
     *
     * For modes that include a full sync, tries each configured [SyncConfig] in
     * order until one succeeds. If all fail, reports the last error.
     */
    suspend fun start(scope: CoroutineScope, mode: SyncMode = SyncMode.Continuous) {
        if (syncJob?.isActive == true) return

        syncJob = scope.launch {
            when (mode) {
                SyncMode.OneShot, SyncMode.Continuous -> {
                    val lastError = tryStartWithFallbacks()
                    if (lastError != null) {
                        _syncState.value = SyncState.Error(lastError.message ?: "Sync failed", lastError)
                        _events.emit(WalletEvent.SyncStateChanged(_syncState.value))
                        _events.emit(WalletEvent.WalletError(lastError.message ?: "Sync failed", lastError))
                    }
                    if (mode == SyncMode.OneShot) {
                        // No polling follows, so release the HTTP client eagerly instead of
                        // holding it open until the caller remembers to call stop().
                        api?.close()
                        api = null
                        return@launch
                    }
                    if (lastError != null) return@launch
                    pollLoop()
                }
                SyncMode.IncrementalOnly -> {
                    // Caller's contract: local storage is already fresh. Bind the API
                    // against the current activeSyncConfig (which may be a fallback
                    // established by a previous Continuous/OneShot sync) without doing
                    // a full scan, mark the kit Synced immediately, then start polling.
                    api?.close()
                    api = buildApi()

                    val syncTime = Clock.System.now().toEpochMilliseconds()
                    _syncState.value = SyncState.Synced(syncTime)
                    _events.emit(WalletEvent.SyncStateChanged(_syncState.value))

                    pollLoop()
                }
            }
        }
    }

    private suspend fun CoroutineScope.pollLoop() {
        while (isActive) {
            delay(pollingInterval)
            performIncrementalSync()
        }
    }

    /**
     * Try each sync config in order. Returns null on success, or the last exception on total failure.
     */
    private suspend fun tryStartWithFallbacks(): Exception? {
        var lastError: Exception? = null
        val warnings = mutableListOf<SyncState.Warning>()

        for ((index, config) in syncConfigs.withIndex()) {
            activeSyncConfig = config
            api?.close()
            api = buildApi()

            try {
                performFullSync(warnings)
                return null // success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                val hasMoreFallbacks = index < syncConfigs.size - 1
                warnings.add(
                    SyncState.Warning(
                        configName = config::class.simpleName ?: "Unknown",
                        message = e.message ?: "Sync failed",
                        cause = e
                    )
                )
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
        blockInfoStorage.getLastBlockInfo()?.height?.let { return it }
        return try {
            api?.getBlockHeight()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun performFullSync(warnings: List<SyncState.Warning> = emptyList()) {
        val currentApi = api ?: throw IllegalStateException("API not initialized")

        _syncState.value = SyncState.Syncing(0.0, "Starting sync...")
        _events.emit(WalletEvent.SyncStateChanged(_syncState.value))

        // Fetch tip metadata in a single call. /blocks returns the 10 most recent
        // blocks with full info; we only need the first.
        val tip = currentApi.getBlocks().firstOrNull()
            ?: error("Esplora /blocks returned an empty response; backend may be misconfigured")
        val blockHeight = tip.height
        val blockInfo = BlockInfo(
            height = tip.height,
            hash = tip.id,
            timestamp = tip.timestamp
        )
        blockInfoStorage.saveBlockInfo(blockInfo)
        _events.emit(WalletEvent.NewBlock(tip.height, tip.id, tip.timestamp))

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
                syncAddress(currentApi, key, address, blockHeight)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Continue with other addresses on error
            }

            processed++
        }

        val syncTime = Clock.System.now().toEpochMilliseconds()
        _syncState.value = SyncState.Synced(syncTime, warnings)
        _events.emit(WalletEvent.SyncStateChanged(_syncState.value))

        // Emit balance update
        val balance = calculateBalance()
        _events.emit(WalletEvent.BalanceUpdated(balance))
    }

    /**
     * Sync a single address using the delta-fetch algorithm:
     *
     * 1. Probe with /address/{addr} (1 call) to read current chain & mempool
     *    tx counts.
     * 2. If neither count changed since last sync, skip remaining work — the
     *    fast path costs only the probe.
     * 3. Otherwise: paginate /txs/chain to fetch only the new confirmed txs
     *    (terminating on the stored cursor), refetch /txs/mempool when nonzero
     *    (handles RBF and mempool→chain promotion), and refetch /utxo so
     *    [TransactionProcessor.processUtxos] can prune any orphaned outputs.
     *
     * On a fresh wallet [WalletPublicKey.lastSyncedChainTipTxid] is null and
     * the pagination naturally walks the full history; on the steady state of
     * an unchanged address the cost is exactly 1 call.
     */
    private suspend fun syncAddress(
        currentApi: BlockchainExplorerApi,
        key: WalletPublicKey,
        address: String,
        blockHeight: Int
    ) {
        val addressInfo = currentApi.getAddress(address)
        val curChain = addressInfo.chainStats.txCount
        val curMempool = addressInfo.mempoolStats.txCount
        val prevChain = key.lastSyncedChainTxCount
        val prevMempool = key.lastSyncedMempoolTxCount
        val prevTip = key.lastSyncedChainTipTxid

        val chainChanged = curChain != prevChain
        val mempoolChanged = curMempool != prevMempool
        // Fast path: skip the rest only when (1) counts match the last sync,
        // (2) mempool is empty — otherwise we must refetch /txs/mempool to
        // catch RBF replacements that swap one txid for another at the same
        // count, and (3) we've recorded sync state at least once. A used key
        // with no recorded cursor (prevTip == null) may carry stale local
        // UTXOs from a Phase 1 sync or a prior reorg-to-zero; force one
        // local-only reconciliation pass to prune them.
        val needsReconciliation = key.isUsed && prevTip == null
        if (!chainChanged && !mempoolChanged && curMempool == 0 && !needsReconciliation) return

        val processor = TransactionProcessor(
            publicKeyManager,
            transactionStorage,
            utxoStorage,
            addressConverter
        )

        val newConfirmed = mutableListOf<ApiTransaction>()
        var newTip = prevTip
        if (chainChanged) {
            if (curChain == 0) {
                // No on-chain history. Clear the cursor so a future re-activation
                // doesn't try to paginate against an orphaned txid.
                newTip = null
            } else {
                var cursor: String? = null
                var pages = 0
                pageLoop@ while (true) {
                    val page = currentApi.getAddressChainTxs(address, cursor)
                    if (pages == 0 && page.isNotEmpty()) {
                        newTip = page.first().txid
                    }
                    for (tx in page) {
                        if (prevTip != null && tx.txid == prevTip) break@pageLoop
                        newConfirmed += tx
                    }
                    if (page.size < CHAIN_TXS_PAGE_SIZE) break@pageLoop
                    pages++
                    if (pages >= MAX_CHAIN_PAGES) break@pageLoop
                    cursor = page.last().txid
                }
            }
        }

        // Mempool: always refetch when nonzero. Bounded at 50 in one call and
        // the only reliable way to detect RBF (replacement txid differs but
        // count may be unchanged) and "tx confirmed without net change".
        val mempoolTxs = if (curMempool > 0) {
            currentApi.getAddressMempoolTxs(address)
        } else {
            emptyList()
        }

        // UTXOs change whenever any tx involving the address moves, so refetch
        // whenever a count changed. For zero-activity addresses we know /utxo
        // returns []; skip the network call and let processUtxos prune locally.
        val utxos = if (curChain + curMempool > 0) {
            currentApi.getAddressUtxos(address)
        } else {
            emptyList()
        }
        processor.processUtxos(address, utxos, key.path, key.scriptType, blockHeight)

        val allNewTxs = newConfirmed + mempoolTxs
        if (allNewTxs.isNotEmpty()) {
            publicKeyManager.markAsUsed(key.path)
            val processedTxs = processor.processTransactions(address, allNewTxs, blockHeight)
            for (tx in processedTxs) {
                if (tx.type == TransactionType.INCOMING) {
                    _events.emit(WalletEvent.TransactionReceived(tx))
                }
            }
        }

        publicKeyManager.recordSyncState(
            path = key.path,
            chainTxCount = curChain,
            mempoolTxCount = curMempool,
            chainTipTxid = newTip
        )
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
                checkUnconfirmedTransactions()

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

    private suspend fun checkUnconfirmedTransactions() {
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
