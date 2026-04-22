# Reduce per-sync API calls to the Esplora backend

## Context

Today's `performFullSync()` in `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/sync/SyncManager.kt:225` issues ~83 calls to the Blockchain Explorer per full sync (3 block-info calls + 2 calls × 40 default gap-limit addresses). Full syncs run on app start, on `refresh()`, and every time the polling loop sees a new block (~6×/hour at chain pace). This is wasteful, slow, and a problem against rate-limited backends like enterprise BlockStream.

The Esplora HTTP API (BlockStream, mempool.space, self-hosted Electrs) does **not** offer a batch endpoint, so we can't collapse the 40 per-address calls into one. But we can:

1. Replace the 3-call block-tip metadata fetch with a single `/blocks` call.
2. Skip per-address `/txs` and `/utxo` for addresses with zero history by using `/address/{addr}` as a cheap probe.
3. Track per-address sync state so we only refetch transactions when the address has actually changed since last sync (Phase 2).

Phase 1 is independently shippable and gets typical full-sync down from ~83 calls to ~45–50. Phase 2 builds on Phase 1's `/address/{addr}` probe and gets steady-state full-sync down to ~41 calls (just the per-address probes) when nothing has changed.

API claims verified against https://github.com/Blockstream/esplora/blob/master/API.md.

---

## Phase 1: gap-address pruning + collapse block-info

### Changes

**`library/src/commonMain/kotlin/io/sourlabs/btc/wallet/sync/BlockchainExplorerApi.kt`**

- Add `getBlocks(startHeight: Int? = null): List<ApiBlock>` — `GET /blocks` (or `/blocks/{startHeight}`), returns 10 most recent blocks in one call. Already-existing `getAddress(address)` (line 94) gets reused, no change needed.
- `ApiBlock.difficulty` (line 286) is currently a non-nullable `Double`. The `/blocks` endpoint may not include it per the spec. Change to `val difficulty: Double = 0.0` to keep deserialization tolerant. (`ignoreUnknownKeys = true` doesn't cover missing required fields.)

**`library/src/commonMain/kotlin/io/sourlabs/btc/wallet/sync/SyncManager.kt`**

- `performFullSync()` (line 225): replace lines 232–241 (the 3 block-info calls) with a single `currentApi.getBlocks().first()`. Use the returned `ApiBlock` to build `BlockInfo(height, hash=id, timestamp)`.
- Per-address loop (lines 254–300): before calling `/txs` and `/utxo`, call `currentApi.getAddress(address)`. If `chainStats.txCount + mempoolStats.txCount == 0`, skip both subsequent calls and continue. Otherwise proceed as today.

### Call-count impact

| Wallet shape | Today | After Phase 1 |
|---|---|---|
| Fresh wallet (40 unused addresses) | 3 + 80 = 83 | 1 + 40 = 41 |
| 5 used + 35 unused | 83 | 1 + 35 + (5 × 3) = 51 |
| All 40 active (worst case) | 83 | 1 + 40 × 3 = 121 |

The worst case is a regression, but it's structurally rare: hitting it requires every gap-limit address to have transaction history, which doesn't occur in normal use.

### Verification

- `./gradlew jvmTest` (fastest)
- Manual: against a known-empty regtest wallet, log API call count over a full sync — expect 41.
- Manual: against a wallet with 1–2 active addresses on testnet, log call count — expect ~45.
- `./gradlew allTests` to cover all platforms.

---

## Phase 2: delta tx fetching

### Storage additions

**`library/src/commonMain/kotlin/io/sourlabs/btc/wallet/models/WalletPublicKey.kt`**

Add three fields (defaulted, so no breaking change for existing callers):

```kotlin
val lastSyncedChainTxCount: Int = 0,
val lastSyncedMempoolTxCount: Int = 0,
val lastSyncedChainTipTxid: String? = null,  // newest confirmed txid seen last sync
```

Update the explicit `equals` (line 57) and `hashCode` (line 75) overrides to include the three new fields.

These live on `WalletPublicKey` rather than a separate sync-state store because: (a) it's 1:1 with the address — no joins, no orphans; (b) `PublicKeyStorage` already persists this object; (c) it's intrinsically per-key sync metadata, parallel to `isUsed`.

**`library/src/commonMain/kotlin/io/sourlabs/btc/wallet/storage/PublicKeyStorage.kt`**

Add one method:

```kotlin
suspend fun updateSyncState(
    path: String,
    chainTxCount: Int,
    mempoolTxCount: Int,
    chainTipTxid: String?
)
```

Implement in `InMemoryPublicKeyStorage` at `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/storage/InMemoryWalletStorage.kt:16` (mirror the `markAsUsed` pattern at line 47 — `keys[path]?.let { keys[path] = it.copy(...) }`).

### API additions

**`library/src/commonMain/kotlin/io/sourlabs/btc/wallet/sync/BlockchainExplorerApi.kt`**

Replace the existing `getAddressTransactions(address)` (line 101) — which returns the full history — with two paginated variants matching the Esplora endpoints:

```kotlin
suspend fun getAddressChainTxs(address: String, lastSeenTxid: String? = null): List<ApiTransaction>
//   GET /address/{address}/txs/chain[/{lastSeenTxid}] — 25 newest-first per page
suspend fun getAddressMempoolTxs(address: String): List<ApiTransaction>
//   GET /address/{address}/txs/mempool — up to 50, no pagination
```

(If any external callers of `getAddressTransactions` exist, leave it as-is to avoid an API break and add the two new methods alongside. Verify by grepping at implementation time.)

### Algorithm

Rewrite the per-address loop in `performFullSync` (`SyncManager.kt:254`):

```
for each key:
  addr = api.getAddress(address)             // Phase 1's probe — required
  curChain   = addr.chainStats.txCount
  curMempool = addr.mempoolStats.txCount
  prevChain   = key.lastSyncedChainTxCount
  prevMempool = key.lastSyncedMempoolTxCount
  prevTip     = key.lastSyncedChainTipTxid

  // Fast path: address unchanged since last sync
  if curChain == prevChain && curMempool == prevMempool && curChain + curMempool > 0:
    continue   // 1 call total for this address
  if curChain + curMempool == 0:
    continue   // Phase 1 case — also 1 call

  // Confirmed delta via cursor pagination
  newConfirmed = []
  newTip = prevTip
  if curChain != prevChain:
    cursor = null
    pages = 0
    loop:
      page = api.getAddressChainTxs(address, lastSeenTxid = cursor)
      if pages == 0 && page.isNotEmpty(): newTip = page.first().txid
      for tx in page:
        if prevTip != null && tx.txid == prevTip: break loop  // caught up
        newConfirmed += tx
      if page.size < 25: break loop                            // walked off end
      if ++pages >= 200: break loop                            // hard cap (~5000 tx)
      cursor = page.last().txid

  // Mempool: always re-fetch when nonzero (handles RBF, mempool->chain promotion)
  mempoolTxs = if curMempool > 0 then api.getAddressMempoolTxs(address) else []

  // UTXOs: only refetch if anything changed
  if curChain != prevChain || curMempool != prevMempool:
    utxos = api.getAddressUtxos(address)
    processor.processUtxos(address, utxos, key.path, key.scriptType, blockHeight)

  if (newConfirmed + mempoolTxs).isNotEmpty():
    publicKeyManager.markAsUsed(key.path)
    processor.processTransactions(address, newConfirmed + mempoolTxs, blockHeight)
    emit TransactionReceived events for incoming new txs

  storage.updateSyncState(key.path, curChain, curMempool, newTip)
```

### Why these specific design choices

- **Termination on `txid == prevTip`, not on count.** Counts can drift under chain reorgs or replica lag; a content-addressed txid is the only stable cursor. Counts are advisory.
- **Three independent termination guards** (known-txid hit, short page <25, hard page cap of 200). The cap protects against pathological cases where `prevTip` was orphaned by a reorg.
- **Capture `newTip` before the loop**, so it's set even if the loop is empty (though this can only happen if the page itself is empty — unusual).
- **Always refetch mempool when `curMempool > 0`.** The endpoint is bounded at 50 txs and a single call. This is the only way to detect RBF (replacement txid is different but count may be unchanged) and "tx confirmed without net change" (one mempool tx promoted to chain while another was added to mempool).
- **UTXO call gated on count change.** Any UTXO change implies a tx involving the address, which moves a count. Safe to skip otherwise.
- **Cold start works without a special branch.** `prevChain=0, prevMempool=0, prevTip=null`. If `curChain>0`, the loop walks all pages until short-page (correct full pull). If both are 0, the empty-address branch fires (Phase 1 behavior). No off-by-one.

### Edge cases worth flagging in the implementation

1. **Stale local mempool txs (RBF / eviction).** A tx we previously stored as PENDING may no longer be in the mempool (replaced by RBF, or evicted for low fee). After processing the latest mempool pull, optionally sweep `transactionStorage.getUnconfirmedTransactions()` for txs not seen on any address's mempool fetch this sync, and mark them appropriately. **Defer to a follow-up** — it's a separate cleanup that doesn't affect the call-count goal.
2. **`markAsUsed` and `updateSyncState` write race.** Both do `key.copy(...)` against the same path. In `performFullSync` they're called sequentially within one loop iteration, so there's no real race. If we ever introduce concurrent per-address sync, consolidate into a single `updateKey` write.
3. **`getAddress` and `/txs/chain` race.** A new block can land between the two calls, so `curChain` may be stale by 1. The txid cursor catches up next sync. Harmless.
4. **Reorg handling is out of scope.** If `prevTip` references an orphaned tx, we walk to the end of history (expensive but bounded by the page cap). Long-term mitigation: validate `prevTip` via `GET /tx/{txid}/status` before using as cursor; on miss, treat as cold start. Not MVP.

### Call-count impact (Phase 1 + Phase 2 together)

| Wallet shape, steady-state poll triggering full sync | Phase 1 only | Phase 1 + Phase 2 |
|---|---|---|
| 40 unused addresses, no activity since last sync | 41 | 41 |
| 5 used (0 new txs since last sync), 35 unused | 51 | 41 |
| 5 used, 1 had a new confirmed tx | 51 | 41 + 1 (`/txs/chain`) + 1 (`/utxo`) = 43 |
| 5 used, 1 has 1 mempool tx | 51 | 41 + 1 (`/txs/mempool`) + 1 (`/utxo`) = 43 |

### Verification

- `./gradlew jvmTest`
- New unit tests for the delta-fetch algorithm: cold start, no-op sync, single new confirmed tx, single new mempool tx, mempool→chain promotion, RBF (mempool count unchanged but content changed). Exercise via a faked `BlockchainExplorerApi` interface or test double.
- Manual: instrument call count during a sync against testnet; verify steady-state poll-triggered full sync of a fresh wallet hits exactly 41 calls.
- `./gradlew allTests`
- `./gradlew publishToMavenLocal`, then update Bad Wallet Client to consume and exercise normal sync flows.

---

## Out of scope / future work

- **WebSocket subscriptions** (mempool.space only) — eliminates polling for steady-state status changes. Bigger architectural shift; defer.
- **Stale-mempool-tx sweep** — see edge case #1 above.
- **Reorg-safe cursor validation** — see edge case #4.
- **`getRawTransaction` and `getMempoolInfo`** — defined on `BlockchainExplorerApi` but never called. Not part of this work; either delete or leave as-is per separate cleanup.
