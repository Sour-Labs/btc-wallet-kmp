package io.sourlabs.btc.wallet.utxo

import fr.acinq.bitcoin.ByteVector32
import io.sourlabs.btc.wallet.models.BlockInfo
import io.sourlabs.btc.wallet.models.ScriptType
import io.sourlabs.btc.wallet.models.UnspentOutput
import io.sourlabs.btc.wallet.storage.InMemoryWalletStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Anchors PR-09 (audit finding H10): confirmations are computed against the
 * live chain tip in [io.sourlabs.btc.wallet.storage.BlockInfoStorage], not
 * stored on each UTXO. As soon as a new block lands, the wallet's view of
 * balance reflects the bumped confirmation count without any sync call.
 */
class UnspentOutputProviderTest {

    private fun makeUtxo(value: Long, blockHeight: Int?): UnspentOutput = UnspentOutput(
        transactionHash = ByteVector32.Zeroes,
        outputIndex = 0,
        value = value,
        scriptPubKey = ByteArray(22),
        scriptType = ScriptType.P2WPKH,
        publicKeyPath = "m/84'/0'/0'/0/0",
        blockHeight = blockHeight,
        isSpendable = true,
    )

    @Test
    fun confirmationsAdvanceWithChainTipWithoutResync() = runTest {
        // Headline acceptance: a UTXO confirmed at height 100 should be reported
        // as having different confirmation counts depending on the current tip,
        // and getBalance() should reclassify it from "unconfirmed" to "spendable"
        // as soon as the tip is advanced — no sync run between calls.
        val storage = InMemoryWalletStorage()
        val provider = UnspentOutputProvider(
            storage = storage.unspentOutputStorage,
            blockInfoStorage = storage.blockInfoStorage,
            confirmationsThreshold = 6,
        )
        storage.unspentOutputStorage.saveUtxo(makeUtxo(value = 1_000_000, blockHeight = 100))

        // No tip stored → confirmations = 0 → unconfirmed
        val noTip = provider.getBalance()
        assertEquals(0, noTip.spendable)
        assertEquals(1_000_000, noTip.unconfirmed)

        // Tip = 100: confirmations = 1, still below the 6-threshold
        storage.blockInfoStorage.saveBlockInfo(BlockInfo(height = 100, hash = "h", timestamp = 0))
        val tip100 = provider.getBalance()
        assertEquals(0, tip100.spendable)
        assertEquals(1_000_000, tip100.unconfirmed)

        // Tip = 105: confirmations = 6 → meets threshold → spendable.
        // No sync, no UTXO mutation, no recomputed snapshot — same UTXO, same
        // storage, just a fresher tip and the wallet's classification flips.
        storage.blockInfoStorage.saveBlockInfo(BlockInfo(height = 105, hash = "h", timestamp = 0))
        val tip105 = provider.getBalance()
        assertEquals(1_000_000, tip105.spendable)
        assertEquals(0, tip105.unconfirmed)
    }

    @Test
    fun mempoolUtxoStaysUnconfirmedRegardlessOfTip() = runTest {
        // A UTXO with no blockHeight (still in mempool) should report 0
        // confirmations against any tip — the storage doesn't know which
        // block it'll land in until the sync confirms.
        val storage = InMemoryWalletStorage()
        val provider = UnspentOutputProvider(
            storage = storage.unspentOutputStorage,
            blockInfoStorage = storage.blockInfoStorage,
            confirmationsThreshold = 1,
        )
        storage.unspentOutputStorage.saveUtxo(makeUtxo(value = 500_000, blockHeight = null))
        storage.blockInfoStorage.saveBlockInfo(BlockInfo(height = 1_000_000, hash = "h", timestamp = 0))

        val balance = provider.getBalance()
        assertEquals(0, balance.spendable)
        assertEquals(500_000, balance.unconfirmed)
    }

    @Test
    fun lockedUtxoIsLockedNotUnconfirmed() = runTest {
        // isSpendable=false trumps the confirmation classification: the UTXO
        // should land in the `locked` bucket, not `unconfirmed`, regardless
        // of the tip.
        val storage = InMemoryWalletStorage()
        val provider = UnspentOutputProvider(
            storage = storage.unspentOutputStorage,
            blockInfoStorage = storage.blockInfoStorage,
        )
        val locked = makeUtxo(value = 250_000, blockHeight = 50).copy(isSpendable = false)
        storage.unspentOutputStorage.saveUtxo(locked)
        storage.blockInfoStorage.saveBlockInfo(BlockInfo(height = 100, hash = "h", timestamp = 0))

        val balance = provider.getBalance()
        assertEquals(0, balance.spendable)
        assertEquals(0, balance.unconfirmed)
        assertEquals(250_000, balance.locked)
    }
}
