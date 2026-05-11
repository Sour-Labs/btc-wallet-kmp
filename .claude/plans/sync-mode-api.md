# Plan: `SyncMode` API for `BitcoinKit.start()`

**Goal:** Expose a `SyncMode` parameter on `BitcoinKit.start()` so callers can pick one-shot, continuous, or incremental-only sync behavior.

**Motivation:** Wallet apps consuming the library need finer control over sync lifetime — e.g. (a) refreshing balances for many wallets at launch without keeping polling loops alive for each, and (b) skipping the initial full sync when switching to a wallet whose cached data is still fresh, while keeping incremental polling on top.

## 1. Add `SyncMode` sealed interface

**File:** new `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/models/SyncMode.kt`

```kotlin
sealed interface SyncMode {
    /** Run one full sync, then stop. No polling. */
    data object OneShot : SyncMode
    /** Run one full sync, then poll incrementally. Current default behavior. */
    data object Continuous : SyncMode
    /** Skip the initial full sync; poll incrementally only. */
    data object IncrementalOnly : SyncMode
}
```

## 2. Thread `SyncMode` through `SyncManager.start`

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/sync/SyncManager.kt`

Change signature:
```kotlin
suspend fun start(scope: CoroutineScope, mode: SyncMode = SyncMode.Continuous)
```

Behavior inside the `syncJob` launch:
- **OneShot**: run `tryStartWithFallbacks()`; if success, return (job ends). If all fallbacks fail, emit `Error` as today. No polling loop.
- **Continuous**: current behavior — `tryStartWithFallbacks()` then polling loop.
- **IncrementalOnly**: skip `tryStartWithFallbacks()`. Bind `api` against `syncConfigs.first()` (create `BlockchainExplorerApi(baseUrl)`, set `activeSyncConfig = syncConfigs.first()`). Immediately set `_syncState = SyncState.Synced(Clock.System.now().toEpochMilliseconds())` and emit `WalletEvent.SyncStateChanged(...)` — caller's contract is that local storage is already fresh, so the kit is "ready" from the moment it starts. Then enter the polling loop.

`tryStartWithFallbacks`, `refresh()`, existing callers with default arg — all unchanged.

### Edge cases
- For `IncrementalOnly`, if `performIncrementalSync` detects a new block it still calls `performFullSync()`. That's desirable — incremental mode should catch up on new blocks. Leave that alone.

## 3. Thread `SyncMode` through `BitcoinKit.start`

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/api/BitcoinKit.kt`

```kotlin
suspend fun start(mode: SyncMode = SyncMode.Continuous) {
    publicKeyManager.initialize()
    syncManager.start(scope, mode)
}
```

Default arg keeps all existing call sites source-compatible.

## 4. Tests

**File:** new `library/src/commonTest/kotlin/.../sync/SyncManagerModeTest.kt` (or extend existing sync tests if any).

Using a fake `BlockchainExplorerApi` / stub:
- **OneShot**: `start(OneShot)` → wait for `Synced` → assert `syncState` is `Synced` and no second call to `getBlockHeight()` happens after the polling interval + buffer.
- **Continuous** (regression): `start(Continuous)` still reaches `Synced` and still polls (at least one extra `getBlockHeight` call after polling interval).
- **IncrementalOnly**: `start(IncrementalOnly)` → skips initial address scan (assert no calls to `getAddressTransactions` on the first pass) → polls → if a new block appears, triggers full sync.

Check if fake/stub infra already exists — if not, may need minimal test scaffolding. Worth scoping before starting.

## 5. Publish

- Bump version in `gradle.properties` (e.g., `0.1.2` → `0.2.0` — new public API, minor bump under 0.x).
- `./gradlew allTests` → pass.
- `./gradlew publishToMavenLocal`.
- Coordinate the version bump with downstream consumers as a follow-up.

## Preconditions
`IncrementalOnly` assumes local storage has prior UTXO/tx/key data — the caller is responsible for picking it only when a previous `Continuous` sync has populated that storage (e.g. `lastSyncTime` is non-null). Document the precondition in kdoc; no runtime protection in the lib.
