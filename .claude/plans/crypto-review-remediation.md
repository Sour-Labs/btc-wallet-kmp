# Crypto/Bitcoin Logic Review — Findings & Remediation Plan

- **Date:** 2026-07-02
- **Reviewed at:** commit `99eb8d4` (main), bitcoin-kmp 0.29.0
- **Scope:** all Bitcoin/cryptographic logic in `library/src/commonMain` + platform `SecureRandom` actuals
- **Method:** manual line-level review of every file in `keys/`, `transactions/`, `utxo/`, `descriptors/`, `sync/`, `storage/`, `core/`, `api/`, `models/`, cross-checked against the actual bitcoin-kmp 0.29.0 sources (Gradle cache). The three money-handling bugs were **empirically confirmed** by a scratch test that ran the real `TransactionSigner`/`TransactionBuilder`/`UnspentOutputSelector` and validated results with bitcoin-kmp's consensus verifier `Transaction.correctlySpends` (test was deleted after the run; reproduce with the harness in Appendix A).

Empirical results from that run:

```
P2WPKH (BIP84):        VALID
P2PKH  (BIP44):        VALID
P2SH-P2WPKH (BIP49):   VALID
P2TR   (BIP86):        INVALID (invalid Schnorr signature)      ← Finding 1
subtractFee/no-change: reportedFee=1500, actualOnChainFee=3000  ← Finding 2
subtractFee amount<fee: built tx with outputs=[-70400, 99400]   ← Finding 3
P2TR proposed fix (untweaked key → signInputTaprootKeyPath): VALID
```

## Task overview

Each task below is one coding session producing one PR. Work top to bottom.
Update Status as tasks land (`todo` → `in progress` → `PR #NN` → `done`).

| # | Task | Priority | Findings | Status |
|---|------|----------|----------|--------|
| 1 | Fix Taproot double-tweak + signature-validity tests | P0 | F1, F4 (tests) | PR #53 |
| 2 | Fix `subtractFeeFromAmount` fee accounting + amount validation | P0 | F2, F3 | PR #54 |
| 3 | Consensus-validity gate after signing | P0 | F4 | PR #55 |
| 4 | Validate extended-key version prefix against network | P1 | F5 | todo |
| 5 | Restore-scan robustness (mnemonic validation + probe failures) | P1 | F7, F8 | todo |
| 6 | Full-sync convergence for gap-limit extensions | P1 | F6 | todo |
| 7 | Redact secrets in `WalletConfig` `toString()` | P1 | F9 | todo |
| 8 | Reorg detection & confirmation rollback | P2 | F10 | todo |
| 9 | Privacy: output-order shuffle + anti-fee-sniping locktime | P2 | F11 | todo |
| 10 | Dead API surface: `SendParams`, `memo`, `lockTime` | P2 | F12 | todo |
| 11 | Document trust/privacy model + logging hygiene | P2 | F13 | todo |

---

## Findings

### F1 (Critical): Every BIP-86 Taproot send produces a consensus-invalid signature

`TransactionSigner.signP2TR` (`transactions/TransactionSigner.kt:185-220`) manually
applies the BIP-341 key-path tweak:

```kotlin
val internalXOnlyKey = privateKey.privateKey.xOnlyPublicKey()
val tweak = internalXOnlyKey.tweak(Crypto.TaprootTweak.KeyPathTweak)
val tweakedPrivateKey = privateKey.privateKey.tweak(tweak)
val sig = Transaction.signInputTaprootKeyPath(tweakedPrivateKey, tx, inputIndex, prevOuts, SigHash.SIGHASH_DEFAULT, null)
```

But bitcoin-kmp 0.29.0's `signInputTaprootKeyPath` **applies the key-path tweak
internally** when `scriptTree == null` (`Transaction.kt:680-684` →
`Crypto.signSchnorr`, `Crypto.kt:180-188`). The signature is therefore made with
a doubly-tweaked key while the scriptPubKey (from `Script.pay2tr(xOnly,
KeyPathTweak)` in `AddressConverter`) commits to the singly-tweaked output key.
Every P2TR spend is rejected by the network ("invalid Schnorr signature").
Fails loudly — no fund loss — but BIP-86 wallets cannot spend at all.
bitcoin-kmp's internal signature self-check doesn't catch it because it
verifies against the same doubly-tweaked key.

**Verified fix** (produced a VALID consensus spend in the harness):

```kotlin
private fun signP2TR(...): Transaction {
    val prevOuts = allUtxos.map { u -> TxOut(Satoshi(u.value), ByteVector(u.scriptPubKey)) }
    val sig = Transaction.signInputTaprootKeyPath(
        privateKey.privateKey, tx, inputIndex, prevOuts, SigHash.SIGHASH_DEFAULT, null
    )
    // witness = ScriptWitness(listOf(sig)) — unchanged
}
```

### F2 (Critical): `subtractFeeFromAmount` + sub-dust change double-counts the fee

When potential change is ≤ dust, `UnspentOutputSelector` absorbs the residual
into the fee: `fee = totalInput - targetAmount`
(`utxo/UnspentOutputSelector.kt:191-196`, same in `selectManual` at 148-153).
`TransactionBuilder.build` then subtracts that same fee from the destination
again: `sendAmount = params.amount - selectionResult.fee`
(`transactions/TransactionBuilder.kt:128-132`). Confirmed: intended fee 1,500
sats → actual on-chain fee 3,000 sats. Silent overpayment, bounded roughly by
`feeWithChange + dust`, on every subtract-fee send that lands in the no-change
branch. The with-change branch is arithmetically correct (fee on-chain ==
selection.fee).

### F3 (Critical): `subtractFeeFromAmount` with amount ≤ fee signs a negative-value output

`TransactionCreator.validateOutgoing` (`transactions/TransactionCreator.kt:152-176`)
skips the dust check entirely when `subtractFeeFromAmount = true`, and neither
the builder nor bitcoin-kmp's `TxOut` rejects a negative amount at construction.
Confirmed: amount=600 at 500 sat/vB built a tx with a **−70,400 sat output**.
Because `create()` calls `recordOutgoingTransaction` *before* broadcast
(`TransactionCreator.kt:210-249`), the guaranteed-to-fail broadcast still
deletes the spent UTXOs and persists a PENDING tx — local state corrupted until
the next full sync reconciles.

### F4 (Critical, systemic): no signature/consensus self-check anywhere

`correctlySpends` appears nowhere in production code or tests. The signer also
never cross-checks that the script it derives from the key matches
`utxo.scriptPubKey`. This is why F1 and F3 shipped invisibly: the existing
tests only check address formats and amounts, never signature validity.

### F5 (Medium): extended-key version prefixes ignored on import

`HDWalletManager.fromExtendedPrivateKey` / `fromExtendedPublicKey`
(`keys/HDWalletManager.kt:187, 212`) call `decode(...)` and discard
`decoded.first` (the version prefix). A `tpub` with `network = MAINNET` (or
xpub with TESTNET, etc.) is silently accepted; addresses get encoded for the
claimed network regardless of the key's actual provenance. The descriptor path
already infers network from the SLIP-132 prefix (`KeyExpressionParser.kt:83`);
the direct path validates nothing.

### F6 (Medium): restore doesn't converge in one full sync

`SyncManager.performFullSync` iterates a snapshot of keys
(`sync/SyncManager.kt:343-345`). Keys derived mid-loop by
`markAsUsed → fillGap` are not scanned until the *next* full sync (next block
or manual refresh). After restoring an active wallet: balance under-reports for
a while, and `receiveAddress()` can hand out an address that is already used
on-chain but not yet discovered (address reuse).

### F7 (Medium): `BitcoinKit.scanWallet` doesn't validate the mnemonic

`api/BitcoinKit.kt:474` calls `fr.acinq.bitcoin.MnemonicCode.toSeed(mnemonic,
passphrase)` directly, skipping validation (`SeedManager.toSeed` validates
first). A typo'd mnemonic scans as an *empty wallet* instead of raising an
error — during restore, exactly when a checksum error matters most.

### F8 (Medium): scanner silently counts a failed probe as Empty

`MultiPurposeScanner.scanChain` (`sync/MultiPurposeScanner.kt:152-173`): a
probe that fails (after `withRetry`'s 3 internal attempts) is counted as
`AddressProbe.Empty` and the walk continues. An active address misclassified
Empty can terminate the gap walk early → restore under-counts balance/used
addresses, silently. Contradicts the class's own doc ("a partial scan can't be
trusted"). Abort (or retry that index) instead of degrading.

### F9 (Medium): secrets leak via data-class `toString()`

`WalletConfig.FromMnemonic` (mnemonic + passphrase), `FromSeed` (seed bytes),
`FromExtendedPrivateKey` (xprv string) are data classes
(`core/WalletConfig.kt`); generated `toString()` prints the secrets if an app
logs the config or a crash reporter serializes it. The codebase already solves
this pattern for `SyncConfig.BlockStream.Auth` (`core/SyncConfig.kt`, redacting
`toString()`) — same treatment needed. Note bitcoin-kmp's `ExtendedPrivateKey`
already redacts (`"<extended_private_key>"`).

### F10 (Low-Medium): reorg handling is partial

- Incremental sync only reacts to `currentHeight > lastBlock.height`
  (`sync/SyncManager.kt:527`); a same-height reorg isn't detected until the
  next block (block hash never compared).
- `TransactionStorage.updateConfirmation` only flips *to* CONFIRMED
  (`sync/TransactionProcessor.kt:106-113`); an orphaned tx keeps stale
  height/status forever. The UTXO set self-heals (full `/utxo` refetch), so
  balances recover; tx history metadata doesn't.

### F11 (Low): deterministic change position; no anti-fee-sniping

`TransactionBuilder.build` always appends change immediately after the
destination output (`transactions/TransactionBuilder.kt:139-149`) — change is
trivially identifiable (privacy leak). `lockTime` is always 0; Core/Electrum
set `lockTime = tipHeight` to discourage fee sniping.

### F12 (Low): dead API surface

`api/SendParams.kt` is referenced nowhere. `TransactionParams.memo` and
`.lockTime` are unreachable from the public API (`TransactionCreator` never
sets them). If `memo` were ever wired up, fee estimation would not account for
the OP_RETURN output size.

### F13 (Low): trust/privacy model undocumented; chatty logs

The wallet fully trusts the Esplora backend (no SPV/merkle verification) and
queries every wallet address against a third-party API by default — fine
architecture, but not stated in README/SECURITY.md. Kermit logs addresses,
txids and balances at info/debug level; for a wallet library, consider gating.

### Reviewed and found correct (no action)

Platform RNGs (JVM/Android `SecureRandom`, iOS `SecRandomCopyBytes`, Linux
`/dev/urandom` + EINTR); BIP-39 validation-before-seed in `SeedManager`;
account-depth enforcement on imports; watch-only fencing (signer constructor +
creator guards); BIP-143 scriptCode for P2WPKH/P2SH-P2WPKH (empirically VALID);
BIP-380 checksum constants/expansion; BIP-67 sortedmulti + P2WSH assembly
(shared codepath); master-fingerprint byte order; dust thresholds match Core
policy; vsize estimates err ~1–2 vB conservative; txid byte-order
(display-order storage ↔ `OutPoint` reversal) consistent; storage mutex
guards; `SyncConfig.Auth` secret redaction.

---

## Remediation plan

General rules for every task:

- One task = one session = one PR, branched off `main`.
- TDD per CLAUDE.md: first write the test that reproduces the finding, watch it
  fail, then fix, then `./gradlew allTests` (all platforms, not just JVM —
  crypto goes through secp256k1-kmp native bindings on iOS/Linux).
- Keep diffs surgical; do not refactor adjacent code.
- Update the Status column in this file as part of each PR.

### Task 1 — Fix Taproot double-tweak + signature-validity tests (P0, F1+F4-tests)

**Files:** `transactions/TransactionSigner.kt`; new test
`commonTest/.../transactions/TransactionSignerConsensusTest.kt`.

1. Add failing tests first, one per script type (P2PKH, P2SH-P2WPKH, P2WPKH,
   P2TR): build a 1-in/1-out spend of a synthetic UTXO paying to the wallet's
   own derived script, sign with `TransactionSigner`, assert
   `signedTx.correctlySpends(mapOf(outPoint to TxOut(...)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)`
   does not throw. Use the Appendix A harness. P2TR fails before the fix; the
   other three pass (keep them as regression cover).
2. Fix `signP2TR`: delete the three manual tweak lines
   (`internalXOnlyKey` / `tweak` / `tweakedPrivateKey`) and pass
   `privateKey.privateKey` directly to `Transaction.signInputTaprootKeyPath(...,
   scriptTree = null)`. Witness construction unchanged.
3. Also cover a 2-input P2TR spend (prevouts ordering is part of the BIP-341
   sighash — `allUtxos` order must match input order).

**Acceptance:** new consensus tests pass on `allTests`; no other diff.
**PR title:** `fix(signing): taproot key-path signatures were double-tweaked`

### Task 2 — Fix `subtractFeeFromAmount` accounting + validation (P0, F2+F3)

**Files:** `transactions/TransactionBuilder.kt`,
`transactions/TransactionCreator.kt`, possibly `utxo/UnspentOutputSelector.kt`;
tests in `TransactionBuilderTest`/`TransactionCreatorTest`.

Required invariants (write tests asserting each, for both change and no-change
selections):

- `destination + change + onChainFee == totalInput` and
  `onChainFee == selection.fee` in every subtract-fee case (kills the
  double-count: today the no-change branch pays `2×fee`).
- Post-subtraction destination amount must be ≥
  `FeeCalculator.dustThreshold(destinationScriptType)`; otherwise throw
  `InvalidAmountException` *before* signing/recording (kills the −70,400 sat
  output).
- Non-subtract-fee paths byte-identical to today (regression tests).

Design decisions to make in-session (document choice in KDoc):

- Cleanest shape: teach the selector about `subtractFeeFromAmount` (target
  coverage = `amount` alone, dest = `amount − fee`) instead of patching the
  builder — today the selector demands `totalInput ≥ amount + fee`, which also
  makes "send my exact balance with subtract-fee" fail with
  InsufficientFunds (only `createSweep` works). Fixing the semantics here is
  in-scope if it stays contained; otherwise fix the double-count minimally and
  note the semantic quirk in KDoc.
- `getSendInfo` must report the same fee/amounts that `create()` produces
  (it currently ignores subtract-fee entirely — takes no such parameter).

**Acceptance:** reproduction tests fail pre-fix, pass post-fix; `allTests`.
**PR title:** `fix(tx): subtract-fee double-counted fee and allowed sub-dust/negative outputs`

### Task 3 — Consensus-validity gate after signing (P0, F4)

**Files:** `transactions/TransactionCreator.kt` (all three paths: `create`,
`createWithUtxos`, `createSweep`); tests in `TransactionCreatorTest`.

After `signer.sign(...)` and **before** `recordOutgoingTransaction(...)`:

```kotlin
val prevOuts = unsignedTx.utxos.associate { it.toOutPoint() to it.toTxOut() }
try {
    signedTx.correctlySpends(prevOuts, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
} catch (e: Exception) {
    throw SigningException("Produced transaction failed consensus validation", e)
}
```

Placement before recording matters: an invalid tx must not delete UTXOs or
persist a PENDING entry (the F3 state-corruption vector). `SigningException`
already exists in `api/WalletException.kt`. Test: hand the creator a UTXO whose
`scriptPubKey` doesn't match its key (wrong-key scenario) and assert
`SigningException` + no storage mutation (UTXOs still present, no PENDING tx).

**Acceptance:** gate throws on corrupt input, storage untouched; happy paths
unchanged; `allTests`.
**PR title:** `feat(tx): verify signed transactions against consensus rules before recording`

### Task 4 — Validate extended-key version prefix vs network (P1, F5)

**Files:** `keys/HDWalletManager.kt` (`fromExtendedPrivateKey`,
`fromExtendedPublicKey`); tests in `HDWalletManagerTest`.

Check `decoded.first` against the expected prefix set for the `network`
parameter: mainnet ⇒ {`xprv`,`yprv`,`zprv`} / {`xpub`,`ypub`,`zpub`}; testnet
variants ⇒ {`tprv`,`uprv`,`vprv`} / {`tpub`,`upub`,`vpub`} (constants exist in
`DeterministicWallet`). Mismatch ⇒ `WalletInitializationException` naming both
sides. Decision to document: whether to also warn/reject on purpose↔SLIP-132
mismatch (e.g. `zpub` with `Purpose.BIP44`) — recommend reject, since both are
caller-supplied and disagreement means one of them is wrong. Keep
`fromDescriptor` behavior unchanged (network is *inferred* there).

**Acceptance:** tpub+MAINNET (and inverse) rejected with clear message; happy
paths pass; `allTests`.
**PR title:** `fix(keys): reject extended keys whose version prefix contradicts the configured network`

### Task 5 — Restore-scan robustness (P1, F7+F8)

**Files:** `api/BitcoinKit.kt` (`scanWallet`), `sync/MultiPurposeScanner.kt`;
tests in `MultiPurposeScannerTest`.

1. `scanWallet`: validate the mnemonic (`MnemonicCode.validate`) before
   `toSeed`; invalid ⇒ `IllegalArgumentException` (same behavior as
   `SeedManager.toSeed`).
2. `scanChain`: never count a failed probe as Empty. Retry the *same index*
   (bounded, e.g. up to `maxConsecutiveFailures` attempts with backoff);
   if it still fails, abort the scan with `ScanException` — consistent with
   the documented "partial scan is worthless" stance. Update the class KDoc,
   which currently describes the failure-as-empty behavior.

**Acceptance:** test — mid-chain probe failure either recovers via retry or
aborts with `ScanException`; never a silently short result. Typo'd mnemonic
throws instead of returning an empty `WalletScanResult`. `allTests`.
**PR title:** `fix(scan): validate mnemonic and stop counting failed probes as empty addresses`

### Task 6 — Full-sync convergence for gap extensions (P1, F6)

**Files:** `sync/SyncManager.kt` (`performFullSync`); tests via subclassed
`BlockchainExplorerApi` fake (pattern already used in `MultiPurposeScannerTest`).

Loop the address-sync pass until stable: after syncing the snapshot, re-read
the key set; if new keys appeared (gap-limit extension from `markAsUsed`), sync
only the new ones; repeat until no new keys, with a bounded iteration count
(e.g. 50 passes) as a runaway guard. Progress reporting should account for the
growing total.

**Acceptance:** test — a fake API with activity at indices beyond the initial
gap window (e.g. index 25 of 20) yields the full balance and marks all used
keys in a *single* `start()`/full sync; `receiveAddress()` after restore
returns a never-used-on-chain address. `allTests`.
**PR title:** `fix(sync): converge gap-limit extensions within a single full sync`

### Task 7 — Redact secrets in `WalletConfig` `toString()` (P1, F9)

**Files:** `core/WalletConfig.kt`; small test.

Override `toString()` on `FromMnemonic`, `FromSeed`,
`FromExtendedPrivateKey` to redact secret fields (mirror
`SyncConfig.BlockStream.Auth`), keeping non-secret fields (network, purpose,
account, gapLimit) visible. Keep `equals`/`hashCode`/`copy` semantics intact
(data classes stay data classes; only `toString` overridden).

**Acceptance:** test asserts `toString()` contains no mnemonic word, no seed
hex, no xprv substring, and no passphrase. `allTests`.
**PR title:** `fix(core): redact secret material from WalletConfig toString()`

### Task 8 — Reorg detection & confirmation rollback (P2, F10)

**Files:** `sync/SyncManager.kt`, `sync/TransactionProcessor.kt`,
`storage/TransactionStorage.kt` (+ in-memory impl); tests with fake API.

1. Incremental poll: also compare the tip *hash* at unchanged height; on
   mismatch, treat as reorg ⇒ full sync.
2. On full sync, when the API reports a locally-CONFIRMED tx as unconfirmed
   (or a stored tx vanishes from the address history), revert status to
   RELAYED and clear blockHeight/timestamp. Needs a storage method to
   downgrade confirmation (today `updateConfirmation` only upgrades) — an
   interface addition, so flag it as a breaking change for custom
   `WalletStorage` implementors in the CHANGELOG.

Scope deliberately excludes deep-history repair (esplora gives no proofs);
goal is: tip-adjacent reorgs converge within one poll cycle.

**Acceptance:** fake-API test simulating a same-height tip swap + orphaned tx
shows status reverted and balance correct after the next poll. `allTests`.
**PR title:** `feat(sync): detect same-height reorgs and roll back orphaned confirmations`

### Task 9 — Output-order shuffle + anti-fee-sniping locktime (P2, F11)

**Files:** `transactions/TransactionBuilder.kt`,
`transactions/TransactionCreator.kt`; tests.

1. Shuffle destination/change output order (cryptographically random via
   `secureRandomBytes`; OP_RETURN can stay last). `changeOutputIndex` and
   `recordOutgoingTransaction` already work positionally — verify with tests
   that change tracking follows the shuffle.
2. Set `lockTime = currentTipHeight` on built transactions (Core/Electrum
   behavior) with sequence semantics already RBF-compatible
   (`0xFFFFFFFD`/`FFFE` both < final). Tip comes from `BlockInfoStorage`; fall
   back to 0 when unknown. Make it a `TransactionParams` option defaulting on.

**Acceptance:** over N builds, change lands in both positions; locktime equals
tip height when available; signatures still consensus-valid (Task 1 tests
re-run). `allTests`.
**PR title:** `feat(tx): randomize output order and add anti-fee-sniping locktime`

### Task 10 — Dead API surface (P2, F12)

**Files:** `api/SendParams.kt`, `transactions/TransactionBuilder.kt`,
`transactions/TransactionCreator.kt`.

Decide and implement one of:
- **Remove** `SendParams` and `TransactionParams.memo` (+ its OP_RETURN branch)
  — simplest, matches "no speculative features" (CLAUDE.md). `lockTime`
  becomes real via Task 9, so keep it.
- Or **wire up** memo end-to-end: creator parameter → params → builder, and
  include the OP_RETURN output in `FeeCalculator`/selector sizing.

Recommend removal unless Bad Wallet Client needs memos. Check
`~/src/Sour Labs/bad-wallet-client/` for usage before deleting; note the minor
version bump if the public type is removed.

**Acceptance:** no unreachable public API remains; `allTests`;
`publishToMavenLocal` + Bad Wallet Client compile check.
**PR title:** `chore(api): remove unused SendParams and unreachable memo path` (or `feat(tx): expose memo`)

### Task 11 — Trust/privacy documentation + logging hygiene (P2, F13)

**Files:** `SECURITY.md`, `README.md`, `sync/SyncManager.kt`,
`sync/BlockchainExplorerApi.kt`, `api/BitcoinKit.kt`.

1. SECURITY.md/README: state the trust model — the configured Esplora backend
   is fully trusted for balances/history (no SPV verification), and all wallet
   addresses are disclosed to it (privacy tradeoff; mitigate by self-hosting).
2. Logging sweep: move address/txid/amount logs from info to debug where they
   aren't already, and note in README that Kermit log output includes
   financial metadata at debug level. No secrets are currently logged (verified
   during review) — keep it that way; mention in CONTRIBUTING.

**Acceptance:** docs updated; log-level sweep compiles; `allTests`.
**PR title:** `docs: document explorer trust model and logging privacy`

---

## Appendix A — Signature-verification harness

Pattern for consensus-validity tests (this exact harness produced the findings;
adapt into `TransactionSignerConsensusTest` in Task 1):

```kotlin
val mnemonic = listOf("abandon","abandon","abandon","abandon","abandon","abandon",
                      "abandon","abandon","abandon","abandon","abandon","about")
val manager = HDWalletManager.fromMnemonic(mnemonic, "", purpose, Network.MAINNET, 0)
val converter = AddressConverter(Network.MAINNET)
val pubkey = manager.derivePublicKey(isExternal = true, index = 0)
val path = manager.getDerivationPath(isExternal = true, index = 0)
val scriptPubKey = converter.createScriptPubKey(pubkey, scriptType)

val utxo = UnspentOutput(
    transactionHash = ByteVector32.fromValidHex("11".repeat(32)),
    outputIndex = 0, value = 100_000L,
    scriptPubKey = scriptPubKey, scriptType = scriptType,
    publicKeyPath = path, blockHeight = 1,
)
val walletKey = WalletPublicKey(
    path = path, purpose = purpose, account = 0, isExternal = true, index = 0,
    publicKey = pubkey, publicKeyHash = Crypto.hash160(pubkey.value),
)
val tx = Transaction(
    version = 2,
    txIn = listOf(TxIn(utxo.toOutPoint(), ByteVector.empty, 0xFFFFFFFDL)),
    txOut = listOf(TxOut(Satoshi(90_000L), ByteVector(scriptPubKey))),
    lockTime = 0,
)
val unsigned = UnsignedTransaction(tx, listOf(utxo), listOf(walletKey), false, null, 10_000L)
val signed = TransactionSigner(manager).sign(unsigned)

// Throws on any consensus-invalid input — this is the assertion.
signed.correctlySpends(
    mapOf(utxo.toOutPoint() to TxOut(Satoshi(utxo.value), ByteVector(scriptPubKey))),
    ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS,
)
```

Key references into bitcoin-kmp 0.29.0 (sources in Gradle cache,
`fr.acinq.bitcoin:bitcoin-kmp:0.29.0` sources jar):

- `Transaction.signInputTaprootKeyPath` applies `KeyPathTweak` internally when
  `scriptTree == null` — `Transaction.kt:680-684`
- `Crypto.signSchnorr` tweaks the private key for any non-null tweak —
  `Crypto.kt:180-188`
- `Script.pay2tr(internalKey, taprootTweak)` commits the singly-tweaked output
  key — `Script.kt:503-506`
- `ExtendedPublicKey.decode` / `ExtendedPrivateKey.decode` return
  `Pair(versionPrefix, key)`; prefix constants at `DeterministicWallet.kt:305-325`
