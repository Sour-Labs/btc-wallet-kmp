# Bitcoin Wallet KMP Library

A Kotlin Multiplatform library for managing Bitcoin wallets. Provides a high-level API for wallet operations including HD key derivation, address generation, UTXO management, transaction creation, and blockchain synchronization.

Built on top of [ACINQ's bitcoin-kmp](https://github.com/ACINQ/bitcoin-kmp) library.

## Features

- **HD Wallet Support** - BIP32/44/49/84/86 hierarchical deterministic key derivation
- **Multiple Address Types** - Legacy (P2PKH), Nested SegWit (P2SH-P2WPKH), Native SegWit (P2WPKH), and Taproot (P2TR)
- **Watch-Only Wallets** - Create wallets from extended public keys (xpub/ypub/zpub)
- **UTXO Selection** - Multiple selection strategies (automatic, oldest-first, largest-first, privacy-optimized)
- **Transaction Creation** - Build, sign, and broadcast transactions with RBF support
- **Blockchain Sync** - Real-time synchronization via Mempool.space API
- **Reactive Events** - StateFlow and SharedFlow for wallet state and events
- **Custom Storage** - Pluggable storage interface for persistence

## Supported Platforms

| Platform | Architectures |
|----------|---------------|
| JVM | x64, arm64 |
| Android | API 24+ (arm64-v8a, armeabi-v7a, x86, x86_64) |
| iOS | arm64, x64, simulator |
| Linux | x64, arm64 |

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
// For Kotlin Multiplatform projects
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.sourlabs.btc:library:0.1.0")
        }
    }
}

// For single-platform projects (JVM/Android)
dependencies {
    implementation("io.sourlabs.btc:library-jvm:0.1.0")  // JVM
    implementation("io.sourlabs.btc:library-android:0.1.0")  // Android
}
```

Make sure you have Maven Central in your repositories:

```kotlin
repositories {
    mavenCentral()
}
```

## Quick Start

```kotlin
import io.sourlabs.btc.wallet.api.BitcoinKit
import io.sourlabs.btc.wallet.core.WalletConfig
import io.sourlabs.btc.wallet.core.SyncConfig
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import kotlinx.coroutines.launch

// 1. Generate a new mnemonic (or use an existing one)
val mnemonic = BitcoinKit.generateMnemonic(24)

// 2. Create wallet configuration
val config = WalletConfig.FromMnemonic(
    mnemonic = mnemonic,
    purpose = Purpose.BIP84,      // Native SegWit (bc1q... addresses)
    network = Network.MAINNET,
    gapLimit = 20
)

// 3. Build the wallet
val wallet = BitcoinKit.builder(config)
    .syncConfig(SyncConfig.MempoolSpace.forNetwork(Network.MAINNET))
    .build()

// 4. Start the wallet (in a coroutine scope)
coroutineScope.launch {
    wallet.start()

    // Get a receive address
    val address = wallet.receiveAddress()
    println("Send Bitcoin to: $address")

    // Check balance
    val balance = wallet.getBalance()
    println("Balance: ${balance.spendable} satoshis")
}
```

## Wallet Configuration

The library supports multiple ways to initialize a wallet:

### From Mnemonic (BIP39)

```kotlin
val config = WalletConfig.FromMnemonic(
    mnemonic = listOf("abandon", "abandon", ..., "about"),  // 12, 15, 18, 21, or 24 words
    passphrase = "",                    // Optional BIP39 passphrase
    purpose = Purpose.BIP84,            // Address type
    network = Network.MAINNET,
    account = 0,                        // BIP44 account index
    gapLimit = 20,                      // Unused address buffer
    confirmationsThreshold = 1          // Min confirmations for spendable UTXOs
)
```

### From Seed

```kotlin
val config = WalletConfig.FromSeed(
    seed = seedBytes,                   // At least 16 bytes
    purpose = Purpose.BIP84,
    network = Network.MAINNET
)
```

### From Extended Private Key

```kotlin
val config = WalletConfig.FromExtendedPrivateKey(
    extendedKey = "xprv9s21ZrQH143K...",  // or yprv/zprv
    purpose = Purpose.BIP84,
    network = Network.MAINNET
)
```

### Watch-Only Wallet

```kotlin
val config = WalletConfig.WatchOnly(
    extendedPublicKey = "zpub6rFR7...",   // or xpub/ypub
    purpose = Purpose.BIP84,
    network = Network.MAINNET
)
```

## Address Types (Purpose)

| Purpose | Script Type | Address Format | Description |
|---------|-------------|----------------|-------------|
| `BIP44` | P2PKH | `1...` | Legacy addresses |
| `BIP49` | P2SH-P2WPKH | `3...` | Nested SegWit |
| `BIP84` | P2WPKH | `bc1q...` | Native SegWit v0 |
| `BIP86` | P2TR | `bc1p...` | Taproot (SegWit v1) |

## Networks

| Network | Description |
|---------|-------------|
| `Network.MAINNET` | Bitcoin mainnet |
| `Network.TESTNET` | Bitcoin testnet |
| `Network.SIGNET` | Bitcoin signet |
| `Network.REGTEST` | Local regtest |

## Core Operations

### Address Management

```kotlin
// Get a fresh receive address
val address = wallet.receiveAddress()

// Get all used addresses
val usedAddresses = wallet.usedAddresses()

// Validate an address
val isValid = wallet.validateAddress("bc1q...")

// Parse address details
val info = wallet.parseAddress("bc1q...")
// info?.scriptType, info?.scriptPubKey
```

### Balance

```kotlin
val balance = wallet.getBalance()

println("Spendable: ${balance.spendable} sats")     // Confirmed, meets threshold
println("Unconfirmed: ${balance.unconfirmed} sats") // Pending confirmations
println("Locked: ${balance.locked} sats")           // Time-locked
println("Total: ${balance.total} sats")
```

### Sending Bitcoin

```kotlin
// Get transaction info before sending
val sendInfo = wallet.sendInfo(
    toAddress = "bc1q...",
    amount = 100_000,           // Amount in satoshis
    feeRate = 25                // Fee rate in sat/vB
)
println("Fee: ${sendInfo?.fee} sats")

// Create and broadcast transaction
val result = wallet.send(
    toAddress = "bc1q...",
    amount = 100_000,
    feeRate = 25,
    rbfEnabled = true,                  // Enable Replace-By-Fee
    subtractFeeFromAmount = false       // Deduct fee from amount?
)

result.onSuccess { txId ->
    println("Transaction sent: $txId")
}.onFailure { error ->
    println("Failed: ${error.message}")
}
```

### Creating Transactions Without Broadcasting

```kotlin
// Create a signed transaction without broadcasting
val createdTx = wallet.createTransaction(
    toAddress = "bc1q...",
    amount = 100_000,
    feeRate = 25
)

println("TxId: ${createdTx.txId}")
println("Size: ${createdTx.vSize} vBytes")
println("Fee: ${createdTx.fee} sats (${createdTx.feeRate} sat/vB)")
println("Raw: ${createdTx.rawTx.toHex()}")

// Broadcast later
val result = wallet.broadcastTransaction(createdTx.rawTx.toHex())
```

### Sweep All Funds

```kotlin
val sweepTx = wallet.createSweepTransaction(
    toAddress = "bc1q...",
    feeRate = 10
)
wallet.broadcastTransaction(sweepTx.rawTx.toHex())
```

### UTXO Selection Strategies

```kotlin
import io.sourlabs.btc.wallet.utxo.SelectionStrategy

wallet.send(
    toAddress = "bc1q...",
    amount = 100_000,
    feeRate = 25,
    strategy = SelectionStrategy.AUTOMATIC       // Default: optimize for fees + privacy
    // strategy = SelectionStrategy.OLDEST_FIRST   // FIFO selection
    // strategy = SelectionStrategy.LARGEST_FIRST  // Minimize number of inputs
    // strategy = SelectionStrategy.SMALLEST_FIRST // Consolidate small UTXOs
    // strategy = SelectionStrategy.PRIVACY_OPTIMIZED // Avoid linking UTXOs
)
```

### Transaction History

```kotlin
// Get all transactions
val transactions = wallet.transactions()

// Filter by type
val incoming = wallet.transactions(type = TransactionType.INCOMING)
val outgoing = wallet.transactions(type = TransactionType.OUTGOING)

// Limit results
val recent = wallet.transactions(limit = 10)

// Get specific transaction
val tx = wallet.getTransaction("txid...")
tx?.let {
    println("Amount: ${it.amount} sats")
    println("Fee: ${it.fee} sats")
    println("Status: ${it.status}")  // PENDING, RELAYED, CONFIRMED, FAILED
    println("Confirmations: ${it.confirmations(currentBlockHeight)}")
}
```

### Fee Estimation

```kotlin
val fees = wallet.getRecommendedFees()
fees?.let {
    println("Fastest (next block): ${it.fastestFee} sat/vB")
    println("Half hour: ${it.halfHourFee} sat/vB")
    println("Hour: ${it.hourFee} sat/vB")
    println("Economy: ${it.economyFee} sat/vB")
    println("Minimum: ${it.minimumFee} sat/vB")
}
```

### Maximum Spendable Amount

```kotlin
// Calculate max amount you can send at a given fee rate
val maxAmount = wallet.maximumSpendableValue(feeRate = 25)
println("Maximum spendable: $maxAmount sats")
```

## Event Handling

The wallet emits reactive events through Kotlin Flows:

```kotlin
// Observe sync state
wallet.syncState.collect { state ->
    when (state) {
        is SyncState.NotSynced -> println("Not synced")
        is SyncState.Syncing -> println("Syncing: ${(state.progress * 100).toInt()}%")
        is SyncState.Synced -> println("Synced at ${state.lastSyncTime}")
        is SyncState.Error -> println("Sync error: ${state.message}")
    }
}

// Observe wallet events
wallet.events.collect { event ->
    when (event) {
        is WalletEvent.BalanceUpdated ->
            println("New balance: ${event.balance.total}")
        is WalletEvent.TransactionReceived ->
            println("Received: ${event.transaction.amount} sats")
        is WalletEvent.TransactionSent ->
            println("Sent: ${event.transaction.txId}")
        is WalletEvent.TransactionStatusChanged ->
            println("Tx ${event.txId}: ${event.oldStatus} -> ${event.newStatus}")
        is WalletEvent.NewBlock ->
            println("New block: ${event.height}")
        is WalletEvent.WalletError ->
            println("Error: ${event.message}")
        is WalletEvent.SyncStateChanged -> { /* handled by syncState flow */ }
    }
}
```

## Custom Storage

By default, the library uses in-memory storage. For persistence, implement the `WalletStorage` interface:

```kotlin
class MyDatabaseStorage : WalletStorage {
    override val publicKeyStorage: PublicKeyStorage = MyPublicKeyStorage()
    override val transactionStorage: TransactionStorage = MyTransactionStorage()
    override val unspentOutputStorage: UnspentOutputStorage = MyUnspentOutputStorage()
    override val blockInfoStorage: BlockInfoStorage = MyBlockInfoStorage()

    override suspend fun clearAll() {
        // Clear all storage
    }
}

// Use custom storage
val wallet = BitcoinKit.builder(config)
    .storage(MyDatabaseStorage())
    .build()
```

## Sync Configuration

```kotlin
// Mainnet (default)
val syncConfig = SyncConfig.MempoolSpace.forNetwork(Network.MAINNET)

// Testnet
val syncConfig = SyncConfig.MempoolSpace.forNetwork(Network.TESTNET)

// Custom Mempool.space instance
val syncConfig = SyncConfig.MempoolSpace(
    baseUrl = "https://my-mempool-instance.com/api",
    pollingIntervalMs = 30_000
)

// Custom API endpoint
val syncConfig = SyncConfig.CustomApi(
    baseUrl = "https://my-api.com",
    pollingIntervalMs = 60_000
)
```

## Wallet Scanning

Scan for existing wallet activity before creating a wallet:

```kotlin
val scanResult = BitcoinKit.scanWallet(
    mnemonic = mnemonic,
    passphrase = "",
    network = Network.MAINNET
)

// scanResult contains detected address types and balances
```

## Lifecycle Management

```kotlin
// Start wallet (initializes keys, starts sync)
wallet.start()

// Manual refresh
wallet.refresh()

// Stop sync operations
wallet.stop()

// Clear all wallet data
wallet.clearData()
```

## Error Handling

The library throws specific exceptions:

```kotlin
try {
    wallet.send(...)
} catch (e: InvalidAddressException) {
    // Invalid destination address
} catch (e: InsufficientFundsException) {
    // Not enough balance
} catch (e: SigningException) {
    // Watch-only wallet or signing failed
} catch (e: BroadcastException) {
    // Network broadcast failed
} catch (e: WalletException) {
    // General wallet error
}
```

## API Reference

### BitcoinKit

The main facade class. Key properties and methods:

| Property/Method | Description |
|----------------|-------------|
| `network` | Current network (MAINNET, TESTNET, etc.) |
| `purpose` | Address type (BIP44, BIP49, BIP84, BIP86) |
| `isWatchOnly` | Whether wallet can sign transactions |
| `syncState` | StateFlow of sync state |
| `events` | SharedFlow of wallet events |
| `start()` | Initialize and start syncing |
| `stop()` | Stop sync operations |
| `refresh()` | Manually trigger sync |
| `receiveAddress()` | Get fresh receive address |
| `getBalance()` | Get current balance |
| `send()` | Create, sign, and broadcast transaction |
| `createTransaction()` | Create signed transaction without broadcast |
| `transactions()` | Get transaction history |
| `getRecommendedFees()` | Get fee estimates |

### Static Methods

```kotlin
BitcoinKit.generateMnemonic(wordCount = 24)  // Generate new mnemonic
BitcoinKit.validateMnemonic(mnemonic)        // Validate mnemonic words
BitcoinKit.scanWallet(mnemonic, ...)         // Scan for existing activity
```

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| [bitcoin-kmp](https://github.com/ACINQ/bitcoin-kmp) | 0.29.0 | Core Bitcoin implementation |
| [secp256k1-kmp](https://github.com/ACI NQ/secp256k1-kmp) | 0.18.0 | Elliptic curve cryptography |
| [Ktor](https://ktor.io) | 3.0.0 | HTTP networking |
| [kotlinx-coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 1.9.0 | Async/await and Flows |
| [kotlinx-serialization](https://github.com/Kotlin/kotlinx.serialization) | 1.7.0 | JSON serialization |
| [kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime) | 0.6.0 | Date/time operations |

## Building from Source

```bash
# Build all targets
./gradlew build

# Run tests
./gradlew allTests

# Platform-specific tests
./gradlew jvmTest
./gradlew iosSimulatorArm64Test
./gradlew linuxX64Test

# Publish to Maven Local
./gradlew publishToMavenLocal
```

## License

```
Copyright 2026 Sour Labs

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
