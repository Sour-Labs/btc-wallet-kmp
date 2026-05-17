package io.sourlabs.btc.wallet.sync

import co.touchlab.kermit.Logger
import io.sourlabs.btc.wallet.api.ScanException
import io.sourlabs.btc.wallet.keys.AddressConverter
import io.sourlabs.btc.wallet.keys.HDWalletManager
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import io.sourlabs.btc.wallet.models.ScriptType
import kotlin.coroutines.cancellation.CancellationException

private val log = Logger.withTag("MultiPurposeScanner")

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
 * Scans multiple BIP purposes to discover existing wallet funds during restoration.
 * This is essential for wallets that may have been used with different address types.
 *
 * Failure handling: each address probe (gap-limit walk) is allowed to fail up to
 * [maxConsecutiveFailures] times in a row before the scan aborts with [ScanException].
 * A partial scan result would be indistinguishable from "no activity" — callers can't
 * tell if absent funds mean "never funded" or "API was down" — so we bail loudly.
 *
 * Individual GET retries (transient 5xx / network blips) are already handled inside
 * [BlockchainExplorerApi.withRetry]; failures that reach this layer have already
 * burned the retry budget.
 */
class MultiPurposeScanner(
    private val seed: ByteArray,
    private val network: Network,
    private val api: BlockchainExplorerApi,
    private val gapLimit: Int = 20,
    private val maxConsecutiveFailures: Int = 3,
) {
    /**
     * Perform a full scan across every standard purpose.
     */
    suspend fun scanAll(): WalletScanResult {
        val purposes = Purpose.allPurposes
        log.i { "scanAll: scanning ${purposes.size} purposes on $network (gapLimit=$gapLimit)" }
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

        log.i { "scanAll: done. totalBalance=$totalBalance sat, recommended=$recommendedPurpose" }
        return WalletScanResult(
            results = results,
            totalBalance = totalBalance,
            recommendedPurpose = recommendedPurpose
        )
    }

    /**
     * Scan a specific purpose. Walks each chain to the gap limit; aborts the
     * whole scan with [ScanException] if a chain hits [maxConsecutiveFailures]
     * consecutive API failures.
     */
    suspend fun scanPurpose(
        purpose: Purpose,
        onProgress: suspend (chain: String, index: Int) -> Unit = { _, _ -> }
    ): PurposeScanResult {
        log.i { "scanPurpose($purpose) — starting" }
        val hdWallet = HDWalletManager.fromSeed(seed, purpose, network, account = 0)
        val addressConverter = AddressConverter(network)
        val scriptType = ScriptType.fromPurpose(purpose)

        val external = scanChain(hdWallet, addressConverter, scriptType, purpose, isExternal = true, onProgress)
        val internal = scanChain(hdWallet, addressConverter, scriptType, purpose, isExternal = false, onProgress)

        log.i {
            "scanPurpose($purpose) — done: externalUsed=${external.usedCount}, internalUsed=${internal.usedCount}, " +
                "txCount=${external.txCount + internal.txCount}, balance=${external.balance + internal.balance} sat"
        }
        return PurposeScanResult(
            purpose = purpose,
            externalAddressesUsed = external.usedCount,
            internalAddressesUsed = internal.usedCount,
            balance = external.balance + internal.balance,
            transactionCount = external.txCount + internal.txCount,
        )
    }

    private data class ChainScanResult(
        val usedCount: Int,
        val balance: Long,
        val txCount: Int,
    )

    private suspend fun scanChain(
        hdWallet: HDWalletManager,
        addressConverter: AddressConverter,
        scriptType: ScriptType,
        purpose: Purpose,
        isExternal: Boolean,
        onProgress: suspend (chain: String, index: Int) -> Unit,
    ): ChainScanResult {
        val chainLabel = if (isExternal) "external" else "internal"
        var usedCount = 0
        var balance = 0L
        var txCount = 0

        var consecutiveEmpty = 0
        var consecutiveFailures = 0
        var index = 0

        while (consecutiveEmpty < gapLimit) {
            onProgress(chainLabel, index)
            val publicKey = hdWallet.derivePublicKey(isExternal = isExternal, index = index)
            val address = addressConverter.toAddress(publicKey, scriptType)

            val outcome = try {
                probeAddress(address).also { consecutiveFailures = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                consecutiveFailures++
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    throw ScanException(
                        "scanPurpose($purpose) aborted: $consecutiveFailures consecutive API failures " +
                            "on $chainLabel chain at index $index. The partial scan is discarded — " +
                            "wallet may still have funds. Retry the scan when the explorer is reachable.",
                        cause = e,
                    )
                }
                log.w(e) {
                    "scanPurpose($purpose) $chainLabel[$index] failed " +
                        "($consecutiveFailures/$maxConsecutiveFailures); counting as empty and continuing"
                }
                // Count toward gap so a persistent partial outage eventually halts the walk
                // (capped above) rather than looping forever on alternating failure/success.
                AddressProbe.Empty
            }

            when (outcome) {
                is AddressProbe.Active -> {
                    usedCount = index + 1
                    txCount += outcome.txCount
                    balance += outcome.balance
                    consecutiveEmpty = 0
                }
                AddressProbe.Empty -> consecutiveEmpty++
            }
            index++
        }
        return ChainScanResult(usedCount, balance, txCount)
    }

    private sealed interface AddressProbe {
        data class Active(val txCount: Int, val balance: Long) : AddressProbe
        data object Empty : AddressProbe
    }

    private suspend fun probeAddress(address: String): AddressProbe {
        val addressInfo = api.getAddress(address)
        val totalTxCount = addressInfo.chainStats.txCount + addressInfo.mempoolStats.txCount
        if (totalTxCount == 0) return AddressProbe.Empty
        val utxos = api.getAddressUtxos(address)
        return AddressProbe.Active(txCount = totalTxCount, balance = utxos.sumOf { it.value })
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
