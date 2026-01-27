# Bitcoin Wallet KMP Library - Product Requirements Document

## Overview

A Kotlin Multiplatform library for managing HD Bitcoin wallets, built on top of ACINQ's bitcoin-kmp library. This library replicates the functionality of Horizontal Systems' bitcoin-kit-android while being fully multiplatform.

**Target Platforms:** JVM, Android (minSdk 24), iOS (x64, arm64, simulator), Linux (x64, arm64)

## Foundation: ACINQ bitcoin-kmp

The ACINQ bitcoin-kmp library (v0.29.0) already provides:
- Bitcoin primitives (keys, transactions, scripts, addresses)
- BIP32 HD key derivation (`DeterministicWallet`)
- BIP39 mnemonic handling (`MnemonicCode`)
- Address generation for all types (P2PKH, P2SH-P2WPKH, P2WPKH, P2TR)
- PSBT support (BIP174)
- All required cryptography (ECDSA, Schnorr, hashing)
- Serialization utilities (Base58, Bech32/Bech32m)

**What we need to build on top:**
- High-level wallet management layer
- Public key pool with gap limit management
- UTXO tracking and coin selection
- Transaction creation workflow
- Synchronization layer (API-based and/or P2P)
- Persistent storage layer

---

## Module Architecture

```
btc-wallet-kmp/
├── library/                              # Core wallet library
│   └── src/commonMain/kotlin/io/sourlabs/btc/wallet/
│       ├── models/                       # Data models
│       ├── core/                         # Core wallet interfaces
│       ├── keys/                         # HD key management
│       ├── utxo/                         # UTXO management
│       ├── transactions/                 # Transaction building/signing
│       ├── sync/                         # Synchronization interfaces
│       ├── storage/                      # Storage interfaces
│       └── api/                          # Public API (BitcoinKit facade)
├── storage-sqldelight/                   # SQLDelight storage implementation
└── sync-api/                             # API-based sync providers
```

---

## Core Features

### 1. Wallet Initialization

**Supported initialization modes:**
- From BIP39 mnemonic words (12/15/18/21/24 words)
- From raw seed bytes
- From extended private key (xprv/yprv/zprv)
- Watch-only from extended public key (xpub/ypub/zpub)

**Configuration options:**
- Network: Mainnet, Testnet, Signet, Regtest
- Purpose: BIP44 (P2PKH), BIP49 (P2SH-P2WPKH), BIP84 (P2WPKH), BIP86 (P2TR)
- Account number (default: 0)
- Gap limit (default: 20)

### 2. HD Key Management

**Key derivation paths:**
- BIP44: `m/44'/coin'/account'/change/index` → P2PKH addresses (1...)
- BIP49: `m/49'/coin'/account'/change/index` → P2SH-P2WPKH addresses (3...)
- BIP84: `m/84'/coin'/account'/change/index` → P2WPKH addresses (bc1q...)
- BIP86: `m/86'/coin'/account'/change/index` → P2TR addresses (bc1p...)

**Gap limit management:**
- Maintain pool of unused keys (default 20)
- Separate tracking for external (receive) and internal (change) chains
- Auto-fill when keys are marked as used

### 3. UTXO Management

**Tracking:**
- Store all UTXOs with metadata (script type, address, confirmation count)
- Link UTXOs to derived public keys
- Track spent/unspent status

**Spendability rules:**
- Confirmed: requires minimum confirmations (default: 6 for incoming)
- Own change outputs: spendable immediately
- Time-locked outputs: check against current block height
- Plugin-locked outputs: extensible locking mechanism

**Balance calculation:**
- Spendable: confirmed and immediately available
- Unconfirmed: pending confirmations
- Locked: timelocked or plugin-locked
- Total: sum of all categories

### 4. Transaction Building

**UTXO selection strategies:**
- Automatic: optimize for fees and privacy
- Oldest first (FIFO)
- Largest first: minimize inputs
- Smallest first: consolidation mode
- Privacy optimized: avoid linking UTXOs
- Manual: user-specified UTXOs

**Transaction construction:**
- Calculate fees based on virtual size (vByte)
- Support subtract-fee-from-amount
- Automatic change output creation (if above dust)
- RBF opt-in (sequence number handling)

**Signing:**
- P2PKH: Legacy ECDSA signatures
- P2SH-P2WPKH: Nested SegWit with witness
- P2WPKH: Native SegWit ECDSA
- P2TR: Taproot key-path with Schnorr signatures

### 5. Transaction Management

**Status tracking:**
- Pending: created but not broadcast
- Relayed: broadcast to network
- Confirmed: included in a block
- Failed: broadcast failed
- Invalid: double-spent or rejected

**RBF (Replace-By-Fee):**
- Speed up: increase fee, same outputs
- Cancel: replace with self-transfer

### 6. Synchronization

**API-based sync (Mempool.space):**
- Address discovery using gap limit
- Fetch transactions and UTXOs from Mempool.space API
- Real-time updates via polling
- Self-hostable option for privacy

**Multi-purpose wallet restoration:**
- When restoring from mnemonic/seed, scan ALL purposes (BIP44/49/84/86)
- Discover funds across different address types
- Allow user to select which purposes to use going forward

**Sync states:**
- NotSynced
- Syncing (with progress)
- Synced
- Error

### 7. Storage Layer

**Entities:**
- PublicKey: derivation path, public key bytes, hash160, used flag
- WalletTransaction: raw tx, metadata, status, amount, fee
- UnspentOutput: txid:vout, value, script, confirmations, spendability
- BlockInfo: height, hash, timestamp

**Platform implementations:**
- Android: Android SQLite driver
- iOS: Native SQLite driver
- JVM/Linux: JDBC SQLite driver

---

## Public API

### BitcoinKit (Main Facade)

```kotlin
class BitcoinKit {
    // State
    val syncState: StateFlow<SyncState>
    val balance: StateFlow<BalanceInfo>
    val events: SharedFlow<WalletEvent>
    val network: Network
    val isWatchOnly: Boolean

    // Lifecycle
    suspend fun start()
    suspend fun stop()
    suspend fun refresh()

    // Addresses
    suspend fun receiveAddress(): String
    suspend fun usedAddresses(): List<String>
    fun validateAddress(address: String): Boolean

    // Balance
    suspend fun getBalance(): BalanceInfo

    // Transactions
    suspend fun transactions(type: TransactionType?, limit: Int?): List<WalletTransaction>
    suspend fun getTransaction(txId: String): WalletTransaction?

    // Sending
    suspend fun send(params: SendParams): WalletTransaction
    suspend fun sendInfo(params: SendParams): SendInfo
    suspend fun maximumSpendableValue(address: String, feeRate: Int): Long

    // RBF
    suspend fun speedUpTransaction(txId: String, newFeeRate: Int): WalletTransaction
    suspend fun cancelTransaction(txId: String): WalletTransaction

    // UTXOs
    suspend fun getUnspentOutputs(): List<UnspentOutput>

    // Builder
    companion object {
        fun builder(config: WalletConfig): Builder
    }
}
```

### Configuration

```kotlin
sealed class WalletConfig {
    data class FromMnemonic(
        val mnemonic: List<String>,
        val passphrase: String = "",
        val purpose: Purpose = Purpose.BIP84,
        val network: Network = Network.MAINNET,
        val account: Int = 0,
        val gapLimit: Int = 20
    )
    // ... FromSeed, FromExtendedKey, WatchOnly
}

sealed class SyncMode {
    data class ApiOnly(val provider: ApiProvider)
    data class Hybrid(val apiProvider: ApiProvider)
    object SPV  // Future
}
```

---

## Data Models

### Core Types

```kotlin
enum class Purpose { BIP44, BIP49, BIP84, BIP86 }
enum class ScriptType { P2PKH, P2SH_P2WPKH, P2WPKH, P2TR, ... }
enum class Network { MAINNET, TESTNET, SIGNET, REGTEST }

data class BalanceInfo(
    val spendable: Long,      // Satoshis
    val unconfirmed: Long,
    val locked: Long,
    val total: Long
)

data class WalletPublicKey(
    val id: String,           // Derivation path
    val account: Int,
    val index: Int,
    val isExternal: Boolean,
    val publicKey: ByteArray,
    val publicKeyHash: ByteArray,
    val isUsed: Boolean
)

data class UnspentOutput(
    val id: String,           // txid:vout
    val transactionHash: ByteArray,
    val outputIndex: Int,
    val value: Long,
    val scriptPubKey: ByteArray,
    val scriptType: ScriptType,
    val confirmations: Int,
    val isSpendable: Boolean
)

data class WalletTransaction(
    val txId: String,
    val rawTransaction: ByteArray,
    val blockHeight: Int?,
    val timestamp: Long?,
    val status: TransactionStatus,
    val type: TransactionType,
    val amount: Long,         // Net change (signed)
    val fee: Long?
)
```

---

## Dependencies

```toml
[libraries]
acinq-bitcoin-kmp = "fr.acinq.bitcoin:bitcoin-kmp:0.29.0"
sqldelight-* = "app.cash.sqldelight:*:2.0.2"
ktor-client-* = "io.ktor:ktor-client-*:3.0.0"
kotlinx-coroutines = "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0"
kotlinx-datetime = "org.jetbrains.kotlinx:kotlinx-datetime:0.6.0"
```

---

## Implementation Phases

### Phase 1: Core Foundation
1. Data models and interfaces
2. HD Wallet Manager (wrapping bitcoin-kmp DeterministicWallet)
3. Public Key Manager with gap limit
4. Address converter for all BIP types
5. SQLDelight storage schema and platform drivers

### Phase 2: Transaction Building
1. UTXO Provider and balance calculation
2. UTXO Selector with coin selection algorithms
3. Transaction Builder
4. Transaction Signer (P2PKH, P2WPKH, P2SH-P2WPKH, P2TR)
5. Dust threshold calculator

### Phase 3: Synchronization
1. Mempool.space API provider
2. Transaction and UTXO processors
3. Sync Manager with address discovery
4. Fee estimation

### Phase 4: Public API
1. BitcoinKit facade with builder
2. Kotlin Flow-based events
3. RBF support
4. Error handling and documentation

### Phase 5: Optional Enhancements (Future)
1. P2P SPV sync
2. Additional API providers (Blockchair, Electrum)
3. PSBT workflow support
4. Multi-account management
5. Plugin system for timelocked outputs

---

## Reference Implementations

This library is based on the architecture of:
- **bitcoin-kit-android** (bitcoincore + bitcoinkit modules): High-level wallet management, SPV sync, transaction building
- **hd-wallet-kit-android**: HD key derivation, mnemonic handling (now provided by bitcoin-kmp)

Key architectural patterns adopted:
- Builder pattern for wallet initialization
- Chain of responsibility for address converters and UTXO selectors
- Repository pattern for storage
- Flow-based reactive state management
- Clean separation between core logic and platform-specific code
