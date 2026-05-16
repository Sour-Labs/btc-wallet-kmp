# OSS Readiness — Task Plan

Companion to [`oss-readiness-audit.md`](./oss-readiness-audit.md). Each PR below is sized for one focused coding session and addresses one or more findings from the audit.

## Decisions captured

- **Q1 / C1:** Delete `SyncConfig.MyUmbrel` entirely. Added for personal testing; no public-facing replacement.
- **Q2 / H5:** Both `fromExtendedPrivateKey` and `fromExtendedPublicKey` require **account-level** keys (BIP-32 depth 3). Reject other depths with a clear error.
- **Q3 / general:** Breaking changes are acceptable on 0.x.
- **Q4 / M7:** Add `Network.TESTNET4` as a separate enum value. Leave `Network.TESTNET` pointing at Testnet3 for now since reliable Testnet4 faucets are still scarce.
- **Q5 / M11:** Mnemonic/seed wiping is best-effort. No new `SecureMnemonic` API.

## PR-by-PR plan

PRs are sized to be reviewable independently. Where two PRs touch the same file, the second rebases trivially on the first. The "Depends on" column lists hard ordering — everything else is parallelizable.

| # | Title | Findings | Breaking? | Depends on |
|---|---|---|---|---|
| PR-01 | OSS readiness cleanup | C1, H13, H14, L5 | No | — |
| PR-02 | Post-send local state — kill address reuse + local double-spend | C2, M9 | No | — |
| PR-03 | Sync robustness — concurrency + retry + lifecycle | H1, H11, H12, M2, M8 | No (M2 minor API change) | — |
| PR-04 | Split `P2SH` and `P2SH_P2WPKH` script types | H2 | **Yes** | — |
| PR-05 | Repair `MultiPurposeScanner` | H3, H4, M6 | **Yes** (`scanWallet` signature) | — |
| PR-06 | Account-level convention for extended-key import | H5 | **Yes** | — |
| PR-07 | Per-script-type fee/size estimation | H6, H7, H8 | No (internal) | — |
| PR-08 | Transaction validation + signer perf | M3, M4, M12 | Minor (exception type) | PR-07 |
| PR-09 | Compute confirmations on read | H10 | No (storage shape changes — only `UnspentOutput`) | — |
| PR-10 | Hash-lookup index for sync | H9 | No | PR-04 (touches same file) |
| PR-11 | Lifecycle ergonomics — `start()` semantics + descriptor parse | M1, M5 | Yes (`stop()` becomes `suspend`) | PR-03 |
| PR-12 | Add `Network.TESTNET4` | M7 | No (additive) | — |
| PR-13 | Cleanup polish | M10, M11, L1, L2, L3, L4, L6, L7 | No | last |

Total: 13 PRs. PR-01 through PR-06 are the high-value first wave. PR-07 through PR-10 are correctness + perf. PR-11 through PR-13 are polish.

---

## PR-01 — OSS readiness cleanup

**Findings:** C1, H13, H14, L5
**Breaking?** No
**Size:** S (docs + config; no logic)

### Scope

- Delete the entire `SyncConfig.MyUmbrel` data class and its companion constants in `library/src/commonMain/kotlin/io/sourlabs/btc/wallet/core/SyncConfig.kt`.
- Remove the `is SyncConfig.MyUmbrel` branches from `SyncManager.kt` (`baseUrl`, `pollingInterval`, `buildApi` `when`s).
- Update README dependency table to match `gradle/libs.versions.toml` exactly. Easiest: drop versions from the table and link to the toml file.
- Update README install snippet to `io.sourlabs.btc:library:0.3.0`.
- In `library/build.gradle.kts`, gate `signAllPublications()`:
  ```kotlin
  if (project.hasProperty("signing.keyId") || System.getenv("ORG_GRADLE_PROJECT_signingKeyId") != null) {
      signAllPublications()
  }
  ```
- In `SyncConfig.MempoolSpace.forNetwork(REGTEST)` and `SyncConfig.BlockStream.forNetwork(REGTEST)`, throw `IllegalArgumentException("REGTEST has no public endpoint; pass an explicit baseUrl via the constructor")` instead of silently returning the mainnet URL.

### Acceptance criteria

- `grep -r "tail5605\|umbrel\|MyUmbrel" library/src/` returns nothing.
- README versions match `libs.versions.toml`.
- `./gradlew publishToMavenLocal` still works without signing env vars set.
- `SyncConfig.BlockStream.forNetwork(Network.REGTEST)` throws.

---

## PR-02 — Post-send local state

**Findings:** C2, M9
**Breaking?** No (additive behavior)
**Size:** M

### Scope

In `TransactionCreator.create()`, `createWithUtxos()`, and `createSweep()`, after `signer.sign(...)` succeeds and before returning to the caller:

1. Mark each selected input's key as used:
   ```kotlin
   for (key in inputKeys) publicKeyManager.markAsUsed(key.path)
   ```
2. Mark the change key (if any) as used.
3. Remove the spent UTXOs from `utxoStorage`:
   ```kotlin
   utxoStorage.deleteUtxos(selection.selectedUtxos.map { it.id })
   ```
4. Persist a `PENDING` `WalletTransaction` so `wallet.transactions()` reflects the send immediately. Set `status = PENDING`; the next sync will transition it to `RELAYED` then `CONFIRMED`.
5. Plumbing: `TransactionCreator` needs access to `utxoStorage` and `transactionStorage`. Inject via constructor; update `BitcoinKit.Builder.build()` accordingly.

Then:
- Delete the dead `TransactionProcessor.markAddressesUsed` method (M9). The new flow handles that side of bookkeeping.

### Edge cases

- If `broadcastTransaction` later fails (in `BitcoinKit.send()`), the local state is now inconsistent (we've reserved the UTXOs but never broadcast). Decide: leave optimistic (next sync reconciles) or roll back the local writes on broadcast failure. Recommend **leave optimistic** — explorer reconciliation is reliable, rollback adds complexity.
- `createTransaction()` without broadcast: the same local reservation happens, which means a user who builds two TXs to inspect them will burn through change keys. Document. (Alternative: only reserve on `send()`, not on `createTransaction()`. But that requires plumbing reservation through to broadcast.)

### Acceptance criteria

- A test that calls `send()` twice in succession (mocked broadcast) and verifies the second call uses a *different* change address and selects a *different* UTXO.
- A test that verifies `wallet.transactions()` includes the just-sent TX with `status = PENDING` before any sync runs.

---

## PR-03 — Sync robustness

**Findings:** H1, H11, H12, M2, M8
**Breaking?** No (M2 turns `stop()` into a suspend fun — technically API change but trivial migration)
**Size:** M

### Scope

1. **H1 — Event flow overflow policy** in `SyncManager.kt:49`:
   ```kotlin
   private val _events = MutableSharedFlow<WalletEvent>(
       replay = 0,
       extraBufferCapacity = 64,
       onBufferOverflow = BufferOverflow.DROP_OLDEST
   )
   ```
   (M8 decision: keep `replay = 0`. Direct UI consumers to `syncState: StateFlow` and `getBalance()` for current state.)

2. **H11 — Retry/backoff in `BlockchainExplorerApi`**:
   - Add a small `withRetry { ... }` helper: 3 attempts, exponential backoff (250ms, 750ms, 2000ms) + ~30% jitter.
   - Wrap GET methods (`getAddress`, `getAddressChainTxs`, `getAddressMempoolTxs`, `getAddressUtxos`, `getTransaction`, `getBlocks`, `getBlockHeight`, `getRecommendedFees`, etc.).
   - **Do not** wrap `broadcastTransaction` — broadcast is meant to be caller-idempotent and silent retries can spam the explorer.
   - Only retry on `ServerResponseException` (5xx) and `IOException`/`HttpRequestTimeoutException`. Not on `ClientRequestException` (4xx) — those don't recover.

3. **H12 — In-memory storage thread safety**:
   - Wrap each of the four `InMemoryXxxStorage` classes with a `kotlinx.coroutines.sync.Mutex` (one per storage).
   - All `suspend` methods acquire the mutex via `mutex.withLock { ... }`.
   - Add a one-paragraph docstring on `WalletStorage` saying "Custom implementations must be safe for concurrent access; the in-memory implementation uses per-storage mutexes."

4. **M2 — `clearData()` race**:
   - Change `SyncManager.stop()` from `fun stop()` → `suspend fun stop()`. Use `syncJob?.cancelAndJoin()`.
   - Change `BitcoinKit.stop()` to `suspend fun stop()`.
   - `clearData()` already awaits `stop()` (it's suspend) once that change lands.

### Acceptance criteria

- No `emit()` in `SyncManager` suspends indefinitely under load. Add a stress test: collector that delays 1s per event, sync emits 100 events, verify completion without deadlock.
- `BlockchainExplorerApi` retries on a stubbed 502 and succeeds on the second try.
- Concurrent reads + writes against `InMemoryPublicKeyStorage` from 10 coroutines don't produce stale data (run the test 100x).
- `stop()` returns only after the sync coroutine has actually exited.

---

## PR-04 — Split `P2SH` and `P2SH_P2WPKH`

**Findings:** H2
**Breaking?** **Yes** — public enum change
**Size:** S-M

### Scope

1. In `ScriptType.kt`, add a new value `P2SH` (generic). Keep `P2SH_P2WPKH` for the wallet's own use.
   ```kotlin
   enum class ScriptType {
       P2PKH, P2SH, P2SH_P2WPKH, P2WPKH, P2TR;
       ...
   }
   ```
2. `Purpose.fromPurpose(BIP49) → P2SH_P2WPKH` (unchanged — the wallet generates nested SegWit specifically).
3. In `AddressConverter.parseAddress`:
   - When `Script.isPay2sh(scriptBytes)`, try to determine if it's the P2SH-P2WPKH redeem-script shape. If we can't tell from the scriptPubKey alone (we can't — the redeem script is in the witness/scriptSig of a *spend*, not in the address), return `ScriptType.P2SH` (generic).
   - The wallet's own change/receive flows still use `P2SH_P2WPKH` because they build the script.
4. In `TransactionProcessor.findWalletKey` and `extractPubKeyHash`: a `P2SH` scriptPubKey from a parsed external address shouldn't match any wallet key (correct — we don't own random P2SH). `P2SH_P2WPKH` keys still match against `P2SH_P2WPKH` script types in the wallet's own outputs.
5. Update `bad-wallet-client` references separately (it's the only consumer).

### Acceptance criteria

- `validateAddress("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy")` → true, classified as `P2SH` (generic, not `P2SH_P2WPKH`).
- A wallet with BIP-49 still correctly generates and recognizes its own `3...` addresses.
- All existing `AddressConverterTest` tests still pass after updating the expected type for the generic P2SH test address.

---

## PR-05 — Repair `MultiPurposeScanner`

**Findings:** H3, H4, M6
**Breaking?** Yes — `scanWallet` signature change, `scan()` return type change
**Size:** M

### Scope

1. **H3 — Fix `scan()` Flow**: Change to:
   ```kotlin
   sealed interface ScanEvent {
       data class Progress(...) : ScanEvent
       data class Done(val result: WalletScanResult) : ScanEvent
   }
   fun scan(): Flow<ScanEvent>
   ```
   The flow now emits Progress events and a final `Done` with the actual results. Alternative if no one uses `scan()`: delete it.

2. **H4 — Bound consecutive failures**: Track consecutive *network* failures separately from consecutive empty addresses. Re-raise the underlying exception after N (e.g. 3) consecutive network failures within a single chain scan. A 404 / "address not found" is not a network failure.

3. **M6 — `BitcoinKit.scanWallet` signature**: Replace
   ```kotlin
   suspend fun scanWallet(
       mnemonic, passphrase, network,
       apiBaseUrl: String?, blockStreamConfig: SyncConfig.BlockStream?
   ): WalletScanResult
   ```
   with
   ```kotlin
   suspend fun scanWallet(
       mnemonic, passphrase = "",
       syncConfig: SyncConfig = SyncConfig.BlockStream.forNetwork(Network.MAINNET)
   ): WalletScanResult
   ```
   The `network` is inferred from `syncConfig` or passed via a separate parameter; pick whichever is cleaner. (Recommend: keep `network` separate since `CustomApi` doesn't carry network info.)

### Acceptance criteria

- `scan()` collector receives at least one `Done` event with a populated `WalletScanResult`.
- Three consecutive simulated timeouts during `scanPurpose` re-raise instead of silently returning "no activity."
- `scanWallet` works with `SyncConfig.MempoolSpace` and `SyncConfig.CustomApi`.

---

## PR-06 — Account-level convention for extended-key import

**Findings:** H5
**Breaking?** **Yes**
**Size:** M

### Scope

1. Rename parameters for clarity:
   - `WalletConfig.FromExtendedPrivateKey.extendedKey` → `accountExtendedPrivateKey`
   - `WalletConfig.WatchOnly.extendedPublicKey` → `accountExtendedPublicKey` (already correct semantically but rename for symmetry; optional)
   - Add a deprecation alias for the old name if desired. Since you're the only consumer, just rename.

2. In `HDWalletManager.fromExtendedPrivateKey`:
   - Decode the xprv.
   - **Reject unless `depth == 3`** with `WalletInitializationException("Expected account-level extended private key (depth 3, e.g. m/84'/0'/0'). Got depth N. Derive the account key yourself before passing it in.")`.
   - Use the decoded key as the account key directly; no further derivation.
   - Pass it to a new private constructor `HDWalletManager(accountPrivateKey, ...)` analogous to the existing watch-only `accountPublicKey` path.

3. In `HDWalletManager.fromExtendedPublicKey`: same depth check.

4. Update `derivePrivateKey` to use the cached account-level extended private key instead of re-deriving from master. (This also folds in M4 — see PR-08 for the signer-side optimization.)

5. Drop the `masterPrivateKey` field and the `getAccountPrivateKey()` helper. The class now holds either an account-level extended private key (signing wallet) or only the public counterpart (watch-only).

6. Update README "From Extended Private Key" section to make the account-level requirement explicit.

7. Update `KeyDerivation` if needed — that's the arbitrary-path utility and should still work on master seeds.

### Acceptance criteria

- Passing a master xprv to `FromExtendedPrivateKey` throws a clear `WalletInitializationException` mentioning the expected depth.
- Passing the account xprv produces the same wallet as `FromMnemonic` for the same seed (`HDWalletManagerTest` updated to verify equivalence).
- README "From Extended Private Key" example uses an account-level xprv with the m/84'/0'/0' path called out.

---

## PR-07 — Per-script-type fee/size estimation

**Findings:** H6, H7, H8
**Breaking?** No (internal)
**Size:** S-M

### Scope

1. Extract `TransactionBuilder.estimateVSize` and the per-script-type input/output sizes into a top-level `internal object FeeCalculator` in `transactions/FeeCalculator.kt`:
   - `inputVSize(scriptType: ScriptType): Int`
   - `outputVSize(scriptType: ScriptType): Int`
   - `estimateVSize(inputs: List<ScriptType>, outputs: List<ScriptType>): Int`
   - `estimateFee(inputs: List<ScriptType>, outputs: List<ScriptType>, feeRateSatPerVb: Long): Long`

2. `UnspentOutputSelector`: take the wallet's `ScriptType` as a constructor parameter. Use `FeeCalculator.estimateFee(...)` instead of the hardcoded `inputCount * 68`. The output script type is the destination's; pass through where available (or default to the wallet's own type for change estimation).

3. `UnspentOutputProvider.getMaximumSpendable`: same treatment. Take destination script type as a parameter (the existing `outputScriptSize: Int` becomes `destinationScriptType: ScriptType?`).

4. `TransactionBuilder.estimateVSize`: delegate to `FeeCalculator`.

5. **H7** — In `UnspentOutputSelector.selectFromSorted`, collapse the unreachable `else if (totalInput >= ...)` branch:
   ```kotlin
   val (fee, change) = if (potentialChange > dustThreshold) {
       feeWithChange to potentialChange
   } else {
       (totalInput - targetAmount) to 0L
   }
   ```

6. **H8** — Either implement the documented `getDustThreshold(scriptSize)` formula:
   ```kotlin
   fun getDustThreshold(scriptSize: Int = 34): Long {
       // 3 * (input vsize for spending the output) * minRelayFee
       // For a P2WPKH-sized output, that's ~3 * 68 * 1 = 204 sat — but 546 is the de-facto standard
       return maxOf(546L, 3L * (8 + 1 + scriptSize) * 1)
   }
   ```
   Or remove the parameter and document `dustThreshold` as a fixed 546 (BIP-174 / Core default).

### Acceptance criteria

- Building a BIP-44 transaction with 5 inputs produces a fee that matches `5 * 148 * feeRate` (approximately, plus overhead), not `5 * 68 * feeRate`.
- A new test in `UnspentOutputSelectorTest` covers BIP-44 selection and verifies the chosen UTXO count matches the higher per-input cost.
- `getDustThreshold(54)` returns a sensibly larger value than `getDustThreshold(22)` (or the function is removed).

---

## PR-08 — Transaction validation + signer perf

**Findings:** M3, M4, M12
**Breaking?** Minor (exception type change for invalid addresses)
**Size:** S-M
**Depends on:** PR-07 (uses the new `FeeCalculator` for dust thresholds per script type)

### Scope

1. **M3 + M12** — Up-front validation in `TransactionCreator.create()` / `createWithUtxos()` / `createSweep()`:
   - `require(amount > 0) { ... }` → throw new `InvalidAmountException` or reuse `IllegalArgumentException`.
   - Compute dust for the destination's script type via `FeeCalculator`; reject if `amount < dust` and `!subtractFeeFromAmount`.
   - `require(feeRate >= 1) { ... }`.
   - **Throw `InvalidAddressException` instead of `IllegalArgumentException`** for the address validation failure. (M12)

2. **M4** — Signer can use the cached account extended private key from `HDWalletManager` (already added in PR-06):
   - `TransactionSigner.signInput` calls `hdWalletManager.derivePrivateKey(isExternal, index)` which now uses the cached account key.
   - Per-input cost drops from "5 derivations" to "2 derivations" (chain + index).

3. Add a constructor parameter on `TransactionCreator` allowing the caller to override the dust threshold for testing (optional).

### Acceptance criteria

- `wallet.send(toAddress, amount = 0, ...)` throws a clear exception (not a confusing "insufficient funds" or "invalid output").
- `wallet.send(invalidAddress, ...)` throws `InvalidAddressException` (not `IllegalArgumentException`).
- A 10-input signing benchmark drops from `~5 * 10 = 50` derivations to `~2 * 10 + 3 = 23` (or similar). Add a microbenchmark if useful; not required.

---

## PR-09 — Compute confirmations on read

**Findings:** H10
**Breaking?** No (internal storage shape)
**Size:** M

### Scope

1. Stop storing `confirmations: Int` on `UnspentOutput`. Keep only `blockHeight: Int?`.
2. Add a helper:
   ```kotlin
   internal fun UnspentOutput.confirmations(currentTipHeight: Int?): Int =
       if (blockHeight == null || currentTipHeight == null) 0
       else (currentTipHeight - blockHeight + 1).coerceAtLeast(0)
   ```
3. `UnspentOutputProvider`: take `BlockInfoStorage` (or a `suspend () -> Int?` tip-height supplier) as a constructor parameter. Use it in `getBalance`, `getSpendableUtxos`, `getConfirmedUtxos`.
4. `SyncManager.calculateBalance`: same treatment using the cached tip.
5. `TransactionProcessor.processUtxos`: don't compute confirmations; just store `blockHeight`.
6. `WalletTransaction.confirmations(currentBlockHeight)` is already correct (computed on read).

### Migration concerns

- The `confirmations` field disappears from `UnspentOutput` — public data class change. Update any tests that reference it.
- `UnspentOutputProvider`'s constructor changes (additional dependency) — `BitcoinKit.Builder.build()` updates the call.

### Acceptance criteria

- A test that creates a UTXO with `blockHeight = 100`, sets tip = 100 (confirmations = 1), then sets tip = 105 (confirmations = 6), without any intermediate sync calls. `getBalance()` reflects the new value immediately.

---

## PR-10 — Hash-lookup index for sync

**Findings:** H9
**Breaking?** No
**Size:** S-M
**Depends on:** PR-04 (touches `findWalletKey` in same file)

### Scope

1. In `PublicKeyManager` or a new `PublicKeyIndex`: maintain a `Map<String, WalletPublicKey>` keyed by `scriptPubKey.toHex()` alongside the existing `findByPublicKeyHash` (which can also become a `Map<HashHex, WalletPublicKey>`).
2. Build the index lazily on first lookup (or update it incrementally when `fillGap`/`saveKey` is called).
3. `TransactionProcessor.findWalletKey`: try the script→key map first; fall back to nothing (don't linear-scan).
4. Drop the per-key `createScriptPubKey` derivation inside `findByScriptPubKey`.

### Edge cases

- The index must invalidate or update when new keys are derived. Easiest: don't cache — rebuild from `getAllKeys()` once per sync (in `TransactionProcessor`). For a wallet with 40 keys this is cheap; for 1000 keys it's still <10ms.

### Acceptance criteria

- Microbenchmark or count of `findWalletKey` lookups per full sync drops from O(addresses × keys) to O(addresses).

---

## PR-11 — Lifecycle ergonomics

**Findings:** M1, M5
**Breaking?** Yes — `start()` semantics change
**Size:** S-M
**Depends on:** PR-03 (uses the new `suspend stop()`)

### Scope

1. **M1 — `start()` awaits first sync**:
   - In `BitcoinKit.start()`, change behavior so that for `SyncMode.OneShot` and `SyncMode.Continuous`, the call suspends until the first full sync completes (either successfully or with an error).
   - For `SyncMode.IncrementalOnly`, current behavior (return immediately after marking Synced) is correct.
   - Implementation: the launched `syncJob` exposes a `CompletableDeferred<Unit>` that signals first-sync completion; `start()` awaits it before returning.
   - Update README to make the semantics explicit.

2. **M5 — Move descriptor parsing out of `WatchOnlyDescriptor` property initializer**:
   - Move `Descriptor.parse(descriptor)` into the `init {}` block (semantically same, but obvious).
   - Optionally add a factory:
     ```kotlin
     companion object {
         fun tryParse(descriptor: String, gapLimit: Int = 20, ...): Result<WatchOnlyDescriptor> = ...
     }
     ```

### Acceptance criteria

- `wallet.start()` returns only after `wallet.syncState.value` has transitioned out of `Syncing` (to either `Synced` or `Error`).
- A test asserting a malformed descriptor throws from `init {}` rather than from a property-initializer chain.

---

## PR-12 — Add `Network.TESTNET4`

**Findings:** M7
**Breaking?** No (additive)
**Size:** S

### Scope

1. Add `TESTNET4` to the `Network` enum. `coinType = 1` (same as Testnet3).
2. `Network.toChain()`: `TESTNET4 -> Chain.Testnet4`.
3. `Network.fromChain(Chain.Testnet4) -> TESTNET4`. (Previously this mapped to `TESTNET`.) Verify nothing depends on the old mapping.
4. Add URLs to `SyncConfig.BlockStream`/`SyncConfig.MempoolSpace`:
   - `https://mempool.space/testnet4/api` (verify Mempool.space serves Testnet4 publicly; if not, throw with a message).
   - Blockstream doesn't currently serve Testnet4 → throw for `BlockStream.forNetwork(TESTNET4)`.
5. Add `displayName = "Bitcoin Testnet4"`.

### Acceptance criteria

- A new test: `HDWalletManager.fromMnemonic(testMnemonic, network = Network.TESTNET4)` produces the same keys as `TESTNET` (coinType is the same).
- `AddressConverter(Network.TESTNET4).toP2WPKHAddress(...)` produces a `tb1q…` address.

---

## PR-13 — Cleanup polish

**Findings:** M10, M11, L1, L2, L3, L4, L6, L7
**Breaking?** No
**Size:** S
**Sequence:** last (rebases cleanly on everything else)

### Scope

1. **M10** — Resolve the `// TODO: Support more languages` in `SeedManager.generateMnemonicCode`. Either:
   - Add a `wordlist: List<String> = englishWordlist` parameter and expose ACINQ's other wordlists. Easy if ACINQ ships them; otherwise just remove the TODO.
   - Decision: probably remove the TODO; non-English wordlist support isn't on the roadmap.
2. **M11 — Best-effort seed wiping**:
   - In `HDWalletManager.fromSeed`, after `DeterministicWallet.generate(seed)`, zero the input `ByteArray` via `seed.fill(0)`. Document that callers should clear their own copies.
   - In `WalletConfig.FromSeed`, mark `seed` as `@Deprecated("Will be cleared after wallet construction; do not retain externally")` or move to a builder pattern. Simpler: just document.
3. **L1** — Replace fully-qualified `io.sourlabs.btc.wallet.descriptors.Descriptor` / `io.sourlabs.btc.wallet.models.Network` references inside `WalletConfig.kt`, `SyncConfig.kt` with regular imports.
4. **L2** — Delete the secondary single-config `SyncManager` constructor; update callers.
5. **L3** — Pick one fee-rate formula:
   - `CreatedTransaction.feeRate = fee.toDouble() / vSize`
   - `WalletTransaction.feeRate = fee.toDouble() / (weight / 4.0)`
   - These are off-by-one for some weight values. Pick the first (vSize-based) and use it in both.
6. **L4** — Replace `BitcoinKit.toHexString` private extension with stdlib `ByteArray.toHexString()` (Kotlin 1.9+). Note: the stdlib version requires `@OptIn(ExperimentalStdlibApi::class)` in some compilations; verify on Native targets.
7. **L7** — In README, add a short "Logging" section listing the Kermit tags (`BitcoinKit`, `SyncManager`, `BlockchainExplorerApi`, `MultiPurposeScanner`) so consumers can suppress per-component.

### Acceptance criteria

- No TODOs in `library/src/commonMain/`.
- `./gradlew detekt` (if installed) reports zero new warnings.
- The two `feeRate` definitions agree on a fixed test input.

---

## Sequencing summary

```
            Independent first wave (parallelizable):
              PR-01 (OSS prep)
              PR-02 (post-send state)
              PR-03 (sync robustness)
              PR-04 (P2SH split)
              PR-05 (scanner repair)
              PR-06 (account-level keys)
              PR-07 (fee estimation)
              PR-09 (confirmations on read)
              PR-12 (Testnet4)

            Sequenced:
              PR-07 → PR-08 (validation uses FeeCalculator)
              PR-04 → PR-10 (both touch findWalletKey)
              PR-03 → PR-11 (uses suspend stop())

            Last:
              PR-13 (polish; clean rebase on everything)
```

If you want a focused first-week push: PR-01, PR-02, PR-04, PR-05, PR-06 deliver all the critical + most high findings affecting correctness and OSS-readiness. Perf and ergonomics (PR-07/08/09/10/11) can roll in over a second week. Testnet4 (PR-12) and polish (PR-13) whenever.
