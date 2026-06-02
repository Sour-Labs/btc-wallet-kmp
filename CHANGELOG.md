# Changelog

All notable changes to this library are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Until `1.0.0`, treat every `0.x → 0.y` bump as potentially breaking.

## [Unreleased]

_No changes yet._

## [0.5.1] - 2026-06-02

### Fixed

- Transient sync failures during continuous polling no longer surface as
  `SyncState.Error`. A single failed incremental poll (a request timeout, a
  transient 5xx, a dropped socket) is now tolerated and the wallet keeps its
  last-synced state; the error is reported only after two consecutive polls
  fail. This stops the UI from flashing a spurious connection error on an
  otherwise-healthy connection. The initial full sync is unaffected — it still
  reports failure immediately once all fallback configs are exhausted.
- A tolerated failure on a new-block poll no longer leaves the wallet stuck in
  `SyncState.Syncing`: `performIncrementalSync` now snapshots the pre-poll state
  and rolls back any intermediate `Syncing` flip when the failure is tolerated.

## [0.5.0] - 2026-05-29

### Added

- Watch-only support for `wsh(sortedmulti(M, KEY1, ..., KEYN))` BIP-380 multisig
  descriptors — the format Bitkey, Sparrow, BlueWallet, and most hardware-
  multisig wallets emit. `WalletConfig.WatchOnlyDescriptor` now accepts these
  alongside single-key descriptors; `BitcoinKit` derives the correct P2WSH
  receive/change addresses and syncs them through the existing gap-limit
  scanner.
- New `MultisigDescriptor` sealed type with a `WshSortedMulti` variant, parsed
  via `MultisigDescriptor.parse(...)` or the new unified
  `OutputDescriptor.parse(...)` entry point that handles both single-key and
  multisig flavours.
- New `MultisigAddressDeriver` for computing the P2WSH address of an M-of-N
  cosigner set at a given `(change, index)` pair (BIP-67 lexicographic sort +
  BIP-141 P2WSH wrapping). Exposes an internal `deriveScripts(...)` helper
  that surfaces all the intermediate byte arrays so `MultisigKeySource` can
  share the same sort / script construction path with no duplication.
- New `WalletKeySource` abstraction in `keys/` so `PublicKeyManager` can switch
  between single-key (`HdWalletKeySource`) and multisig (`MultisigKeySource`)
  derivation strategies. Single-key behaviour is preserved verbatim.
- `ScriptType.P2WSH` variant. `AddressConverter.parseAddress` now recognises
  62-character `bc1q...` / `tb1q...` P2WSH bech32 addresses on the destination
  side.

### Changed

- `PublicKeyManager`'s constructor now takes a `WalletKeySource` instead of an
  `HDWalletManager` directly. Consumers using the public `BitcoinKit.builder(...)`
  API are unaffected. Callers constructing `PublicKeyManager` themselves —
  primarily internal tests — must wrap their `HDWalletManager` in
  `HdWalletKeySource(...)`.
- `WalletConfig.WatchOnlyDescriptor.parsedDescriptor` (the legacy single-key
  accessor) now throws `IllegalStateException` when the parsed descriptor is
  multisig. Callers that need to handle both flavours should read the new
  `parsedOutputDescriptor: OutputDescriptor` field and pattern-match the
  sum type.
- `WalletPublicKey` gains optional `scriptPubKey: ByteArray?` and
  `scriptTypeOverride: ScriptType?` fields. When set (currently only by
  `MultisigKeySource`) they take precedence over the legacy
  derive-from-pubkey-and-purpose path so address resolution and on-chain
  matching work for outputs where a single pubkey can't reconstruct the
  script.
- `AddressConverter.createScriptPubKey(WalletPublicKey)` overload added —
  short-circuits to the stored `scriptPubKey` when present and falls back to
  the legacy derivation otherwise. The legacy
  `createScriptPubKey(PublicKey, ScriptType)` and `toAddress(PublicKey, ScriptType)`
  overloads now throw on `P2WSH` since a single pubkey can't produce a P2WSH
  script.
- The `wsh()` rejection message from `Descriptor.parse` now points callers at
  `MultisigDescriptor.parse` / `OutputDescriptor.parse` instead of a generic
  "not supported."

### Notes for multisig users

- Multisig support is **watch-only**. `kit.send(...)` and
  `kit.createTransaction(...)` throw on multisig wallets — there is no PSBT
  export flow yet, so signing must happen on the hardware wallet itself.
- Only `wsh(sortedmulti(...))` is accepted. Bare `multi(...)`,
  `wsh(multi(...))` (unsorted), and `wsh(...)` wrapping miniscript are
  rejected with a typed `DescriptorException.Unsupported` that points the
  caller at the canonical sortedmulti form.

## [0.4.0] - 2026-05-24

First public release. Earlier `0.x` versions were never published to a public
repository, so this entry captures the API as it stands at the first Maven
Central upload.

### Added

- `Network.TESTNET4` plus a `SyncConfig.MempoolSpace` source — Blockstream
  doesn't serve Testnet4, so Mempool.space is the default sync source for it.
- BIP-380 output-descriptor parsing for watch-only wallet import.
- `KeyDerivation` API for deriving keys at arbitrary BIP-32 paths.
- Multiplatform logging via [Kermit](https://kermit.touchlab.co/), tagged per
  major component (`BitcoinKit`, `SyncManager`, `BlockchainExplorerApi`,
  `MultiPurposeScanner`).
- Blockstream Explorer Enterprise support: OAuth2 `client_credentials`
  exchange, automatic token attachment, transparent re-auth on 401.
- `SyncMode` parameter on `BitcoinKit.start()` for callers that want to control
  the initial sync behavior.
- Fallback sync providers via `BitcoinKit.builder().addFallbackSyncConfig(...)`
  — primary failures are reported as `WalletEvent.WalletError` and the next
  configured provider is tried.
- Sync efficiency: single-call block-tip lookup, per-address probing that only
  fetches full history when activity changed, delta tx fetching, and a cached
  block height.

### Changed

- `BitcoinKit.start()` now suspends until the first sync attempt has finished
  (`Synced` or `Error`), so balance/transaction/receive-address calls work
  immediately on return without observing `syncState`.
- `BitcoinKit.stop()` now suspends until the sync coroutine has actually
  exited, eliminating a race between `stop()` and subsequent state mutations.
- `WalletConfig.FromExtendedPrivateKey` and `WalletConfig.WatchOnly` now
  require **account-level** extended keys (BIP-32 depth 3). Master xprvs are
  rejected — deriving the account silently from a master could produce the
  wrong wallet, so callers must do that derivation themselves.
- `bitcoin-kmp` and Kermit are exposed as `api` dependencies, so consumers can
  use their types without re-declaring them.
- `ScriptType.P2SH` is now distinct from `P2SH_P2WPKH` (previously conflated).
- UTXO confirmation counts are computed from the current chain tip on read
  rather than stored on the UTXO — eliminates a class of stale-confirmation
  bugs.
- Fee and transaction-size estimation goes through a shared `FeeCalculator`
  that knows per-script-type input/output sizes.

### Fixed

- `TransactionCreator` validates amount, fee rate, and dust thresholds up
  front, returning `InvalidAmountException` instead of failing deep in
  construction.
- Post-send local state is recorded immediately so the next `receiveAddress()`
  call doesn't return an address that was just used.
- The restoration scanner aborts after too many consecutive API failures
  (`ScanException`) instead of looping silently; `BitcoinKit.scanWallet`
  accepts a `SyncConfig` for parity with normal wallet construction.
- Stale UTXOs are cleared when an address probe reports zero activity (handles
  the wallet-was-emptied-elsewhere case).
- `BlockchainExplorerApi` surfaces a clear error when `/blocks` returns an
  empty array instead of crashing with a deserialization exception.
- `Auth.toString()` masks `clientSecret` so it can't leak into logs.

### Performance

- `TransactionProcessor.findWalletKey` uses a hash-lookup index instead of a
  linear scan over every wallet key.

### Security

- Documented that Blockstream Enterprise `clientSecret` must **not** be shipped
  in distributed client binaries — anyone with the APK/IPA can extract it.
  Production setups should proxy through a backend.

[Unreleased]: https://github.com/Sour-Labs/btc-wallet-kmp/compare/v0.5.1...HEAD
[0.5.1]: https://github.com/Sour-Labs/btc-wallet-kmp/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/Sour-Labs/btc-wallet-kmp/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/Sour-Labs/btc-wallet-kmp/releases/tag/v0.4.0
