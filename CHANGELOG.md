# Changelog

All notable changes to this library are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Until `1.0.0`, treat every `0.x → 0.y` bump as potentially breaking.

## [Unreleased]

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

[Unreleased]: https://github.com/Sour-Labs/btc-wallet-kmp/compare/main...HEAD
