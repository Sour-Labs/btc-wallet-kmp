# OSS Readiness Audit — btc-wallet-kmp

Audit performed: 2026-05-16
Audited version: 0.3.0 (commit `171db3a`, branch `main`)
Scope: full source tree under `library/src/`, build files, README, GitHub workflows.

The library is in respectable shape for a 0.3.0. BIP-39/32/44/49/84/86 derivation, the BIP-86 taproot tweak, BIP-380 descriptor parsing/checksum, and the per-platform SecureRandom implementations all look correct, with BIP-39 / BIP-84 test vectors anchored to spec. The issues below are concentrated in the layers *above* the bitcoin-kmp primitives — sync, transaction state management, and a handful of API ergonomics rough edges.

---

## What's solid (so you can calibrate)

- **BIP-39 vectors are correct.** `SeedManagerTest.testToSeedMatchesBip39TestVector` and `testToSeedWithPassphraseMatchesBip39TestVector` anchor to the canonical `5eb00bbd…` / `c55257c3…` seeds.
- **BIP-84 vector is anchored end-to-end.** `DescriptorIntegrationTest.wpkhDescriptorMatchesBip84FirstReceiveAddress` derives the published `bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu` from the abandon-about mnemonic via the descriptor pipeline.
- **BIP-86 taproot tweak is right.** `AddressConverter.toP2TRAddress` uses `Crypto.TaprootTweak.KeyPathTweak`, which is the key-path-only (no script tree) tweak from BIP-86. P2TR signing in `TransactionSigner.signP2TR` correctly applies the same tweak to the private key.
- **BIP-380 descriptor parser is well-scoped** and rejects unsupported wrappers with clear error types (`DescriptorException.Unsupported`, `.Malformed`, `.InvalidChecksum`). Rejection cases for `multi`, `sortedmulti`, `wsh`, `tr(KEY,TREE)`, raw hex pubkey, broken fingerprint, malformed path step are all tested.
- **SecureRandom is platform-correct:** `java.security.SecureRandom` on JVM/Android, `SecRandomCopyBytes(kSecRandomDefault, ...)` on iOS, `/dev/urandom` with EINTR + short-read handling on Linux.
- **`Auth.toString()` overridden** in `SyncConfig.BlockStream.Auth` to mask `clientSecret`, preventing leaks via crash reports or accidental `println`.
- **Sync delta algorithm is well-thought-out.** The `chain_tx_count` + `mempool_tx_count` + cursor-txid scheme in `SyncManager.syncAddress` reduces a steady-state full sync to one probe per address. The comments are precise about why each branch exists.
- **HTTP client uses `expectSuccess = true`** so non-2xx responses throw instead of being parsed as data — this catches Blockstream's "400 Block not found" → confusing downstream parse error pattern that's bitten others.
- **Fallback sync configs** are tried in order with structured `SyncState.Warning`s surfacing per-failure reason on success.
- **Comments are appropriately used** — they explain *why* (precondition, prior bug, BIP rationale) rather than *what*, with very few exceptions.

---

## Critical findings

### C1. Personal Tailscale hostname shipped as a default URL

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/core/SyncConfig.kt:128-158`

```kotlin
data class MyUmbrel(
    val baseUrl: String = "http://umbrel.tail5605a5.ts.net:3006/api",
    ...
```

The class name (`MyUmbrel`) and the hostname `tail5605a5.ts.net` leak the maintainer's Tailnet identity into a public artifact. Tailnet names are stable and can be used to identify or probe the node. Three companion constants embed the same hostname.

**Fix options:**
- Delete `MyUmbrel` entirely. It is structurally identical to `CustomApi` — same fields, same shape.
- Rename to `LocalNode(baseUrl: String)` with no default and update the README to "Bring your own self-hosted Esplora/Electrs."

Either way: the hostname must not ship.

---

### C2. Address reuse and local UTXO double-spend window after `send()`

**Files:**
- `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/transactions/TransactionCreator.kt:188-222, 234-284, 290-329`
- `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/keys/PublicKeyManager.kt:43-46, 145-154`

After `wallet.send(...)` produces a signed, broadcast transaction, the code never:
1. Marks the chosen **change key** as used (`publicKeyManager.markAsUsed(changeKey.path)`).
2. Marks each **spent input's key** as used.
3. Removes the **spent UTXOs** from local storage (`utxoStorage.deleteUtxo(...)`).
4. Saves a local `PENDING`/`RELAYED` `WalletTransaction` so history reflects the send immediately.

`getChangePublicKey()` always returns `unusedKeys.minByOrNull { it.index }`. Until the explorer indexes the broadcast and the next sync runs (`pollingIntervalMs` = 30s by default, but indexing can take minutes), the same change address is returned for every subsequent `send()`.

**Consequences:**
- **Privacy:** two sends in quick succession reuse the change address, linking them on-chain.
- **Local double-spend window:** the same UTXOs remain spendable locally and a second `createTransaction` will select them again. The explorer will reject the second broadcast as `txn-mempool-conflict`, but the failure surface is poor.
- **UX:** the wallet's view of `getBalance()` and `transactions()` is stale until the next sync. Users see "I sent it but my balance hasn't moved."

**Fix:** In `TransactionCreator.create()` / `createWithUtxos()` / `createSweep()`, immediately after `signer.sign(...)` succeeds and *before* returning to the caller, mark the input keys + change key used, remove the spent UTXOs, and persist a `PENDING` `WalletTransaction`. The next sync will reconcile.

---

## High findings

### H1. `MutableSharedFlow` for events can deadlock the sync loop

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/sync/SyncManager.kt:49`

```kotlin
private val _events = MutableSharedFlow<WalletEvent>(extraBufferCapacity = 64)
```

Default `onBufferOverflow = BufferOverflow.SUSPEND`. If no one is collecting `events`, or a UI collector is slow, `_events.emit(...)` inside `performFullSync` / `syncAddress` will suspend, blocking the sync coroutine. The sync loop publishes ~5+ events per full sync, so a 64-slot buffer can fill within a handful of cycles.

**Fix:** `MutableSharedFlow<WalletEvent>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)`. Consider `replay = 1` for `SyncStateChanged` / `BalanceUpdated` so late subscribers see current state — but those are also exposed on `syncState: StateFlow`, so probably keep `replay = 0` and direct UI consumers to the `StateFlow` for state-y data.

---

### H2. P2SH addresses are misclassified as P2SH-P2WPKH

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/keys/AddressConverter.kt:88-94`

```kotlin
Script.isPay2sh(scriptBytes) -> ScriptType.P2SH_P2WPKH
```

`Script.isPay2sh` returns true for *any* P2SH script (multisig, time-locked contracts, generic). Parsing a `3...` multisig address labels it as nested-SegWit. `parseAddress` and `validateAddress` are public API that consumers call on arbitrary destinations.

**Fix options:**
- Split `ScriptType.P2SH_P2WPKH` into `P2SH` (generic) and `P2SH_P2WPKH` (nested SegWit specifically). Inspect the redeem script when available to disambiguate.
- Keep one `P2SH` value (since the wallet only generates P2SH-P2WPKH itself) and document that "P2SH here means generic" externally.

The first is more correct but breaks the public enum.

---

### H3. `MultiPurposeScanner.scan(): Flow<ScanProgress>` is broken

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/sync/MultiPurposeScanner.kt:71-87`

```kotlin
fun scan(): Flow<ScanProgress> = flow {
    val results = mutableListOf<PurposeScanResult>()
    for ((index, purpose) in purposes.withIndex()) {
        val result = scanPurpose(purpose) { ... emit(...) }
        results.add(result)
    }
    // ← flow ends here; `results` is never emitted or returned
}
```

The list is computed but never reaches the caller. The function is effectively a write-only progress emitter. `scanAll()` is the actually-used entry point.

**Fix:** Either change the return type to `Flow<ScanEvent>` with a sealed `Progress | Done(WalletScanResult)` shape, or delete the method.

---

### H4. `MultiPurposeScanner.scanPurpose` silently swallows API errors

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/sync/MultiPurposeScanner.kt:158-160, 189-191`

```kotlin
} catch (_: Exception) {
    consecutiveEmpty++
}
```

A network blip is indistinguishable from "address has no history." During wallet restoration this produces false-negative scans: addresses 18, 19, 20 fail with transient timeouts → scanner thinks gap limit was reached → returns "no activity found" when the user actually has funds.

**Fix:** Re-raise after a bounded number of *consecutive* failures (e.g. 3), or after any failure if `gapLimit < 5`. Distinguish HTTP 404-like "not found" from network errors.

---

### H5. `fromExtendedPrivateKey` and `fromExtendedPublicKey` make opposite assumptions about input level

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/keys/HDWalletManager.kt:155-197`

- `fromExtendedPrivateKey` assumes the input is the **master** key and re-derives `m/purpose'/coin'/account'`.
- `fromExtendedPublicKey` assumes the input is the **account** key and uses it directly.

This is asymmetric and undocumented. If a user pastes an account-level `xprv` from a hardware wallet (which most exports are), the code derives `m/84'/0'/0'/84'/0'/0'` from it — wrong wallet, silent failure mode. If a user pastes a master `xpub` (rare but possible), they'd need to feed it through derivation manually, also undocumented.

**Fix options:**
- **Account-level convention for both** (matches descriptor world). Breaking change for FromExtendedPrivateKey users with master xprv.
- **Master-level convention for both.** Breaking change for FromWatchOnly users with account xpub — they'd have to feed `xpub_master` instead, which most hardware wallets don't expose.
- **Auto-detect via depth field.** The BIP-32 serialized key embeds `depth` (0 = master, 3 = account for m/p'/c'/a'). Read it and branch.
- **Add `keyLevel: KeyLevel` parameter** to both, default `ACCOUNT` for public and `MASTER` for private to preserve current behavior, but explicit.

---

### H6. Fee/size estimation hardcodes P2WPKH input size

**Files:**
- `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/utxo/UnspentOutputSelector.kt:210-227`
- `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/utxo/UnspentOutputProvider.kt:65-83`

```kotlin
val inputSize = inputCount * 68  // P2WPKH only
```

BIP-44 P2PKH inputs are ~148 vBytes, BIP-49 P2SH-P2WPKH ~91 vBytes, BIP-86 P2TR ~58 vBytes. For BIP-44 wallets the selector under-estimates by ~50%, producing transactions that pay well below the requested fee rate. `TransactionBuilder.estimateVSize` already does this correctly per script type — `UnspentOutputSelector` and `UnspentOutputProvider` should share that function (or take the wallet's `scriptType` as a constructor argument).

---

### H7. Unreachable branch in `selectFromSorted`

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/utxo/UnspentOutputSelector.kt:160-178`

```kotlin
if (totalInput >= targetAmount + feeWithoutChange) {
    val potentialChange = totalInput - targetAmount - feeWithChange
    val (fee, change) = if (potentialChange > dustThreshold) {
        feeWithChange to potentialChange
    } else if (totalInput >= targetAmount + feeWithoutChange) {  // always true
        (totalInput - targetAmount) to 0L
    } else {
        continue  // unreachable
    }
```

The outer `if` already established the inner `else if` condition is true. The `continue` branch is dead code. Collapse to a binary if/else.

---

### H8. `getDustThreshold(scriptSize)` ignores its parameter

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/utxo/UnspentOutputSelector.kt:232-237`

```kotlin
fun getDustThreshold(scriptSize: Int = 34): Long {
    // Standard dust calculation: 3 * (input vsize) * minRelayFee
    return dustThreshold  // parameter unused
}
```

Either implement the documented formula or drop the parameter. Currently misleading.

---

### H9. Hash lookups are O(N) per vout per tx

**Files:**
- `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/storage/InMemoryWalletStorage.kt:43-45`
- `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/sync/TransactionProcessor.kt:253-272`

For every input and output of every synced tx, we either linear-scan the keys map (`findByPublicKeyHash`) or, for P2SH-P2WPKH / P2TR, recompute every wallet key's scriptPubKey and compare. For 40 keys × 100 txs that's 4,000 derivations per sync; for a recovered wallet with hundreds of keys × thousands of historical txs this becomes the dominant cost.

**Fix:** Build a `Map<HexString, WalletPublicKey>` keyed by `scriptPubKey.toHex()` once per sync (or maintain it incrementally as keys are added to `PublicKeyStorage`).

---

### H10. UTXO `confirmations` field goes stale between syncs

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/sync/TransactionProcessor.kt:75-94`

```kotlin
val confirmations = if (apiUtxo.status.confirmed && apiUtxo.status.blockHeight != null) {
    currentBlockHeight - apiUtxo.status.blockHeight + 1
} else { 0 }
```

`confirmations` is computed once at sync time and stored on the `UnspentOutput`. `UnspentOutputProvider.getBalance()` reads it directly. Between polling intervals (default 30s), a UTXO that crossed the confirmation threshold still reports `confirmations < threshold` and is misclassified as `unconfirmed`.

**Fix:** Store only `blockHeight` (already there) and compute `confirmations` on read against the current tip from `BlockInfoStorage`. Or, less invasively, recompute on every poll cycle even when no other changes detected.

---

### H11. No retry/backoff in `BlockchainExplorerApi`

A single 5xx during a full sync surfaces as `SyncState.Error`. Esplora-style backends are notoriously flaky under load (especially `blockstream.info`).

**Fix:** Wrap GET requests in a small retry with exponential backoff + jitter (e.g. 3 retries, base 500ms). Do *not* retry `POST /tx` — broadcast must be idempotent on the *caller's* side and double-submission could spam logs.

---

### H12. In-memory storage isn't thread-safe

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/storage/InMemoryWalletStorage.kt`

The storage classes use plain `mutableMapOf` mutated from the sync coroutine and read from user-initiated coroutines (`getBalance`, `transactions`, etc.). On JVM this mostly returns stale data without crashing; on Kotlin/Native the semantics are undefined.

**Fix:** Guard each storage with a `Mutex`, or back the maps with a thread-safe primitive. Document that custom `WalletStorage` implementations must handle concurrent access.

---

### H13. README dependency versions are stale; install snippet uses wrong version

**File:** `README.md:43, 524-530`

- Install snippet: `io.sourlabs.btc:library:0.1.0`. Current `version` in `library/build.gradle.kts:11` is `0.3.0`.
- Dependency table: `secp256k1-kmp 0.18.0` (actual: 0.22.0), `Ktor 3.0.0` (actual: 3.4.0), `kotlinx-coroutines 1.9.0` (actual: 1.10.2), `kotlinx-serialization 1.7.0` (actual: 1.10.0), `kotlinx-datetime 0.6.0` (no longer in deps — `kotlin.time.Clock` is used instead).

For a freshly-public repo this is the first thing readers compare. Either regenerate the table from `libs.versions.toml` (could be a Gradle task) or remove versions from the README entirely.

---

### H14. Maven Central publishing won't work as-shipped

**File:** `library/build.gradle.kts:88-99`

`signAllPublications()` is commented out with a (reasonable) explanation about maintainer-only signing keys. But Sonatype/Central refuses unsigned artifacts, so `./gradlew publishToMavenCentral` will fail — including for the maintainer until they uncomment.

**Fix:** Gate the call on env-var presence:

```kotlin
if (project.hasProperty("signing.keyId") || System.getenv("ORG_GRADLE_PROJECT_signingKeyId") != null) {
    signAllPublications()
}
```

Document the env vars in README under "Publishing."

---

## Medium findings

### M1. `BitcoinKit.start()` returns immediately, hiding sync errors

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/api/BitcoinKit.kt:78-82`

`syncManager.start(scope, mode)` launches a job in `scope` and returns. `start()` is `suspend`, suggesting it waits; it doesn't (beyond `publicKeyManager.initialize()`). Callers wanting "wait for first full sync" must observe `wallet.syncState.first { it is Synced || it is Error }`.

**Fix:** Either await the initial sync (especially for `SyncMode.OneShot`), or rename to non-suspending `start()` and document explicitly.

---

### M2. `clearData()` races against the sync loop

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/api/BitcoinKit.kt:298-302`

```kotlin
suspend fun clearData() {
    stop()  // syncJob?.cancel() but not joined
    storage.clearAll()
}
```

The sync job may still be writing UTXOs/transactions when `clearAll()` runs. `stop()` should become `suspend fun stop()` and `syncJob?.cancelAndJoin()`.

---

### M3. `buildSweep` and `TransactionCreator.create` don't validate amount/dust

- `buildSweep`: only checks `require(sendAmount > 0)`. A 547-sat output that clears zero will be relayed but might be rejected by some node policies.
- `TransactionCreator.create`: no check for `amount > 0`, `amount > dust(toAddress.scriptType)`, or `feeRate >= 1`. Result: signed transactions that fail at broadcast with generic mempool errors.

**Fix:** Add validation in `TransactionCreator` before UTXO selection. Throw `InvalidAddressException` / new `InvalidAmountException` for clear failure modes.

---

### M4. Account-level private key re-derived per input during signing

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/transactions/TransactionSigner.kt:44-62`

`hdWalletManager.derivePrivateKey` walks `purpose' → coin' → account' → chain → index` per input. For a 10-input transaction that's 50 derivations instead of the necessary 23. Cache the account key in `HDWalletManager` or pass it to the signer.

---

### M5. `WalletConfig.WatchOnlyDescriptor` parses inside data-class property initializer

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/core/WalletConfig.kt:141-159`

```kotlin
data class WatchOnlyDescriptor(...) : WalletConfig() {
    private val parsed: Descriptor = Descriptor.parse(descriptor)  // throws
    ...
}
```

Property-initializer side effects in data classes surprise users. `Descriptor.parse` throws `DescriptorException`, surfacing as an unfamiliar wrapper exception in the constructor call stack.

**Fix:** Either move parsing into `init {}` block (semantically same, but more obviously a side effect), or add a factory function `WatchOnlyDescriptor.tryParse(...): Result<WatchOnlyDescriptor>`.

---

### M6. `scanWallet` hardcodes `BlockchainExplorerApi` regardless of `SyncConfig` kind

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/api/BitcoinKit.kt:405-431`

Signature accepts `apiBaseUrl: String?` and `blockStreamConfig: SyncConfig.BlockStream?`, ignoring `MempoolSpace` / `CustomApi`. The simpler API is `scanWallet(mnemonic, passphrase, syncConfig: SyncConfig)`.

---

### M7. `Network.TESTNET` maps to deprecated `Chain.Testnet3`

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/models/Network.kt:40`

Testnet3 was effectively replaced by Testnet4 in 2024 (Testnet3 has serious mining/spam issues that broke the 20-minute rule and made coin acquisition unreliable). bitcoin-kmp 0.29 exposes `Chain.Testnet4`.

**Fix options:** Default `TESTNET → Testnet4`, or add `Network.TESTNET4` as a separate value and migrate users. The latter is non-breaking but more clutter.

---

### M8. `events` `SharedFlow` has no `replay`

For state-y events like `BalanceUpdated`, late subscribers (common in UI lifecycle code) miss the most recent value. `replay = 1` is friendly. (But: `syncState` is already a `StateFlow`, and balance is available via `getBalance()` — so maybe just direct consumers to those for current state, and keep events as fire-and-forget.)

---

### M9. `markAddressesUsed` in `TransactionProcessor` is dead code

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/sync/TransactionProcessor.kt:295-324`

Never called. Either wire it into the sync flow (in place of the per-address `publicKeyManager.markAsUsed` inside `SyncManager.syncAddress`) or delete.

---

### M10. `TODO: Support more languages` in `SeedManager`

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/keys/SeedManager.kt:65`

Either implement (ACINQ ships all 10 BIP-39 wordlists) or remove the TODO. For a 0.x OSS readme this looks like incomplete intent.

---

### M11. Mnemonic and seed material not cleared

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/core/WalletConfig.kt` (FromSeed / FromMnemonic)

Seed `ByteArray` and mnemonic `List<String>` live on the heap for the wallet's lifetime. Kotlin doesn't make zeroing easy cross-platform, but at least:
- Wipe seed bytes inside `HDWalletManager.fromSeed` once the master key is derived.
- Don't expose `seed: ByteArray` as a long-lived property on `WalletConfig.FromSeed`.

This is "best-effort" hygiene; KMP doesn't offer locked-memory primitives.

---

### M12. `InvalidAddressException` is documented but `IllegalArgumentException` is thrown

**File:** `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/transactions/TransactionCreator.kt:135-137, 169-172, 235-237`

```kotlin
throw IllegalArgumentException("Invalid address: $toAddress")
```

The README (`Error Handling` section) tells users to catch `InvalidAddressException`. Use the documented type — it's already defined in `WalletException.kt`.

---

## Low findings

### L1. Fully-qualified imports inside files

`WalletConfig.kt` and `SyncConfig.kt` reference `io.sourlabs.btc.wallet.models.Network`, `io.sourlabs.btc.wallet.descriptors.Descriptor`, etc. inline rather than as imports. Style nit.

### L2. `SyncManager` secondary constructor

The single-config secondary constructor in `SyncManager.kt:37-44` adds API surface. `listOf(config)` at the call site is clearer.

### L3. `CreatedTransaction.feeRate` vs `WalletTransaction.feeRate` use different formulas

`CreatedTransaction.feeRate = fee.toDouble() / vSize`
`WalletTransaction.feeRate = fee.toDouble() / weight * 4`

Both compute sat/vB but via different paths. The second is `fee / (weight/4) = fee * 4 / weight`, which equals the first only when `vSize = weight/4` exactly (off-by-one for some weight values). Pick one.

### L4. `BitcoinKit.toHexString` duplicates stdlib

Kotlin 1.9+ has `ByteArray.toHexString()` in the stdlib (`kotlin.io.encoding.HexFormat`). The custom implementation in `BitcoinKit.kt:304-307` can be replaced.

### L5. `MempoolSpace` / `BlockStream` `forNetwork(REGTEST)` returns mainnet URL

`SyncConfig.kt:35, 98, 123` — `REGTEST -> DEFAULT_MAINNET_URL`. Better: throw or `require()` an explicit override.

### L6. `inceptionYear = "2026"` is consistent with the LICENSE; OK.

### L7. `Logger.withTag(...)` at file scope means consumers can't suppress per-component

Minor — document the tag list so users can use `Logger.setSeverity(Severity.Info, tag = "BitcoinKit")`.

---

## Findings summary table

| # | Severity | Area | One-line |
|---|----------|------|----------|
| C1 | Critical | OSS hygiene | Personal Tailscale URL in `MyUmbrel` |
| C2 | Critical | TX state | Address reuse + local UTXO double-spend window after `send()` |
| H1 | High | Concurrency | `MutableSharedFlow` SUSPEND can deadlock sync |
| H2 | High | Address parsing | P2SH misclassified as P2SH-P2WPKH |
| H3 | High | Scanning | `MultiPurposeScanner.scan()` Flow is broken |
| H4 | High | Scanning | Scanner swallows API errors → false negatives |
| H5 | High | Key import | xprv master-level vs xpub account-level mismatch |
| H6 | High | Fees | Hardcoded P2WPKH input size in selector/provider |
| H7 | High | Selector | Dead `else if` branch |
| H8 | High | Selector | `getDustThreshold(scriptSize)` ignores parameter |
| H9 | High | Perf | O(N) hash lookup per vout |
| H10 | High | Sync | UTXO confirmations stale between syncs |
| H11 | High | Network | No retry/backoff on flaky explorer |
| H12 | High | Concurrency | In-memory storage not thread-safe |
| H13 | High | Docs | README versions stale, install snippet wrong |
| H14 | High | Publishing | Maven Central refuses unsigned; gate needed |
| M1 | Medium | API | `start()` doesn't await first sync |
| M2 | Medium | Lifecycle | `clearData` races sync loop |
| M3 | Medium | Validation | Amount/dust/feeRate not validated |
| M4 | Medium | Perf | Account key re-derived per input |
| M5 | Medium | API | Descriptor parsing in data-class initializer |
| M6 | Medium | API | `scanWallet` ignores Mempool/Custom configs |
| M7 | Medium | Network | Testnet3 (deprecated) is default |
| M8 | Medium | Events | No `replay` on SharedFlow |
| M9 | Medium | Hygiene | Dead `markAddressesUsed` method |
| M10 | Medium | Docs | TODO for more BIP-39 languages |
| M11 | Medium | Security | Mnemonic/seed not zeroed |
| M12 | Medium | API | `InvalidAddressException` documented but `IAE` thrown |
| L1 | Low | Style | Fully-qualified imports |
| L2 | Low | Style | Redundant SyncManager constructor |
| L3 | Low | Inconsistency | Two feeRate formulas |
| L4 | Low | Cleanup | Custom `toHexString` duplicates stdlib |
| L5 | Low | API | `forNetwork(REGTEST)` silently returns mainnet URL |
| L6 | — | — | inceptionYear OK |
| L7 | Low | Logging | Document Kermit tags |
