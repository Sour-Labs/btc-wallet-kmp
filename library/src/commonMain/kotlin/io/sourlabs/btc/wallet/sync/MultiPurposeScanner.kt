package io.sourlabs.btc.wallet.sync

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PublicKey
import io.sourlabs.btc.wallet.keys.AddressConverter
import io.sourlabs.btc.wallet.keys.HDWalletManager
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import io.sourlabs.btc.wallet.models.ScriptType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Result of scanning a single purpose/address type.
 */
data class PurposeScanResult(
    val purpose: Purpose,
    val externalAddressesUsed: Int,
    val internalAddressesUsed: Int,
    val balance: Long,
    val transactionCount: Int
)

/**
 * Complete wallet restoration scan result.
 */
data class WalletScanResult(
    val results: List<PurposeScanResult>,
    val totalBalance: Long,
    val recommendedPurpose: Purpose?
) {
    /**
     * Purposes that have any activity.
     */
    val activePurposes: List<Purpose>
        get() = results.filter { it.transactionCount > 0 || it.balance > 0 }.map { it.purpose }

    /**
     * Whether any funds were found.
     */
    val hasFunds: Boolean
        get() = totalBalance > 0
}

/**
 * Progress update during scanning.
 */
data class ScanProgress(
    val currentPurpose: Purpose,
    val currentChain: String, // "external" or "internal"
    val currentIndex: Int,
    val totalPurposes: Int,
    val completedPurposes: Int
)

/**
 * Scans multiple BIP purposes to discover existing wallet funds during restoration.
 * This is essential for wallets that may have been used with different address types.
 */
class MultiPurposeScanner(
    private val seed: ByteArray,
    private val network: Network,
    private val api: MempoolSpaceApi,
    private val gapLimit: Int = 20
) {
    /**
     * Scan all standard purposes to discover wallet activity.
     * @return Flow of progress updates, completing with final scan result
     */
    fun scan(): Flow<ScanProgress> = flow {
        val purposes = Purpose.allPurposes
        val results = mutableListOf<PurposeScanResult>()

        for ((index, purpose) in purposes.withIndex()) {
            val result = scanPurpose(purpose) { chain, keyIndex ->
                emit(ScanProgress(
                    currentPurpose = purpose,
                    currentChain = chain,
                    currentIndex = keyIndex,
                    totalPurposes = purposes.size,
                    completedPurposes = index
                ))
            }
            results.add(result)
        }
    }

    /**
     * Perform a full scan and return the result directly.
     */
    suspend fun scanAll(): WalletScanResult {
        val purposes = Purpose.allPurposes
        val results = mutableListOf<PurposeScanResult>()

        for (purpose in purposes) {
            val result = scanPurpose(purpose) { _, _ -> }
            results.add(result)
        }

        val totalBalance = results.sumOf { it.balance }
        val recommendedPurpose = results
            .filter { it.transactionCount > 0 || it.balance > 0 }
            .maxByOrNull { it.balance }
            ?.purpose
            ?: Purpose.BIP84 // Default to native SegWit if no activity

        return WalletScanResult(
            results = results,
            totalBalance = totalBalance,
            recommendedPurpose = recommendedPurpose
        )
    }

    /**
     * Scan a specific purpose.
     */
    suspend fun scanPurpose(
        purpose: Purpose,
        onProgress: suspend (chain: String, index: Int) -> Unit = { _, _ -> }
    ): PurposeScanResult {
        val hdWallet = HDWalletManager.fromSeed(seed, purpose, network, account = 0)
        val addressConverter = AddressConverter(network)
        val scriptType = ScriptType.fromPurpose(purpose)

        var externalUsed = 0
        var internalUsed = 0
        var totalBalance = 0L
        var txCount = 0

        // Scan external chain
        var consecutiveEmpty = 0
        var index = 0
        while (consecutiveEmpty < gapLimit) {
            onProgress("external", index)

            val publicKey = hdWallet.derivePublicKey(isExternal = true, index = index)
            val address = addressConverter.toAddress(publicKey, scriptType)

            try {
                val addressInfo = api.getAddress(address)
                val totalTxCount = addressInfo.chainStats.txCount + addressInfo.mempoolStats.txCount

                if (totalTxCount > 0) {
                    externalUsed = index + 1
                    txCount += totalTxCount
                    consecutiveEmpty = 0

                    // Get UTXOs for balance
                    val utxos = api.getAddressUtxos(address)
                    totalBalance += utxos.sumOf { it.value }
                } else {
                    consecutiveEmpty++
                }
            } catch (_: Exception) {
                consecutiveEmpty++
            }

            index++
        }

        // Scan internal chain (change addresses)
        consecutiveEmpty = 0
        index = 0
        while (consecutiveEmpty < gapLimit) {
            onProgress("internal", index)

            val publicKey = hdWallet.derivePublicKey(isExternal = false, index = index)
            val address = addressConverter.toAddress(publicKey, scriptType)

            try {
                val addressInfo = api.getAddress(address)
                val totalTxCount = addressInfo.chainStats.txCount + addressInfo.mempoolStats.txCount

                if (totalTxCount > 0) {
                    internalUsed = index + 1
                    txCount += totalTxCount
                    consecutiveEmpty = 0

                    // Get UTXOs for balance
                    val utxos = api.getAddressUtxos(address)
                    totalBalance += utxos.sumOf { it.value }
                } else {
                    consecutiveEmpty++
                }
            } catch (_: Exception) {
                consecutiveEmpty++
            }

            index++
        }

        return PurposeScanResult(
            purpose = purpose,
            externalAddressesUsed = externalUsed,
            internalAddressesUsed = internalUsed,
            balance = totalBalance,
            transactionCount = txCount
        )
    }

    /**
     * Quick check if any purpose has funds (stops at first finding).
     */
    suspend fun quickCheck(): Purpose? {
        for (purpose in Purpose.allPurposes) {
            val hdWallet = HDWalletManager.fromSeed(seed, purpose, network, account = 0)
            val addressConverter = AddressConverter(network)
            val scriptType = ScriptType.fromPurpose(purpose)

            // Just check first few addresses
            for (i in 0 until 5) {
                val publicKey = hdWallet.derivePublicKey(isExternal = true, index = i)
                val address = addressConverter.toAddress(publicKey, scriptType)

                try {
                    val addressInfo = api.getAddress(address)
                    if (addressInfo.chainStats.txCount > 0 || addressInfo.mempoolStats.txCount > 0) {
                        return purpose
                    }
                } catch (_: Exception) {
                    // Continue checking
                }
            }
        }
        return null
    }
}
