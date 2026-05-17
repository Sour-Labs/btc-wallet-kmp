package io.sourlabs.btc.wallet.storage

import fr.acinq.bitcoin.ByteVector32
import io.sourlabs.btc.wallet.keys.AddressConverter
import io.sourlabs.btc.wallet.keys.HDWalletManager
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import io.sourlabs.btc.wallet.models.ScriptType
import io.sourlabs.btc.wallet.models.UnspentOutput
import io.sourlabs.btc.wallet.models.WalletPublicKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Anchors PR-03 (audit finding H12): the in-memory storage classes must be safe
 * for concurrent access from the sync coroutine and user-initiated coroutines.
 *
 * `runTest` is cooperative-concurrent rather than truly parallel, so this verifies
 * that overlapping suspend operations don't observe stale or inconsistent state —
 * which is the failure mode the per-storage Mutex prevents. The mutex correctness
 * is the same under true threading.
 */
class InMemoryWalletStorageConcurrencyTest {

    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    @Test
    fun concurrentPublicKeyOpsConverge() = runTest {
        val hd = HDWalletManager.fromMnemonic(testMnemonic, "", Purpose.BIP84, Network.MAINNET)
        val storage = InMemoryWalletStorage().publicKeyStorage

        // Seed with 50 keys on the external chain.
        val seeded = (0 until 50).map { i ->
            val pk = hd.derivePublicKey(isExternal = true, index = i)
            WalletPublicKey(
                path = hd.getDerivationPath(true, i),
                purpose = hd.purpose,
                account = hd.account,
                isExternal = true,
                index = i,
                publicKey = pk,
                publicKeyHash = ByteArray(20) { it.toByte() },
                isUsed = false,
            )
        }
        seeded.forEach { storage.saveKey(it) }

        // Mix concurrent writes with concurrent reads — the launches interleave their
        // suspend points; without the per-storage mutex this would risk seeing
        // half-mutated state (and on JVM, ConcurrentModificationException during
        // a filter traversal mid-update).
        coroutineScope {
            repeat(25) { i ->
                launch { storage.markAsUsed(seeded[i].path) }
                launch { storage.findByPath(seeded[i + 25].path) }
                launch { storage.getAllKeys() }
                launch { storage.getUnusedKeys(isExternal = true) }
            }
        }

        val all = storage.getAllKeys()
        assertEquals(50, all.size, "no keys lost or duplicated")
        assertEquals(25, all.count { it.isUsed }, "exactly 25 keys marked used")
    }

    @Test
    fun concurrentUtxoSavesAndDeletesConverge() = runTest {
        val converter = AddressConverter(Network.MAINNET)
        val hd = HDWalletManager.fromMnemonic(testMnemonic, "", Purpose.BIP84, Network.MAINNET)
        val storage = InMemoryWalletStorage().unspentOutputStorage

        // 100 distinct UTXO IDs.
        val utxos = (0 until 100).map { i ->
            val pk = hd.derivePublicKey(isExternal = true, index = i % 20)
            UnspentOutput(
                transactionHash = ByteVector32.fromValidHex(i.toString(16).padStart(64, '0')),
                outputIndex = 0,
                value = 1_000_000L,
                scriptPubKey = converter.createScriptPubKey(pk, ScriptType.P2WPKH),
                scriptType = ScriptType.P2WPKH,
                confirmations = 6,
                publicKeyPath = hd.getDerivationPath(true, i % 20),
                blockHeight = 100,
                isSpendable = true,
            )
        }

        // Concurrent: half save, half read, the remainder delete after saving.
        val saved = coroutineScope {
            utxos.map { utxo ->
                async {
                    storage.saveUtxo(utxo)
                    storage.getAllUtxos().size  // racy read, just shouldn't crash
                    utxo.id
                }
            }.awaitAll()
        }

        assertEquals(100, saved.toSet().size)
        assertEquals(100, storage.getAllUtxos().size)

        // Now concurrent deletes of half the set.
        val toDelete = utxos.take(50).map { it.id }
        coroutineScope {
            toDelete.forEach { id ->
                launch { storage.deleteUtxo(id) }
                launch { storage.getAllUtxos() }
            }
        }

        val remaining = storage.getAllUtxos()
        assertEquals(50, remaining.size)
        assertTrue(remaining.none { it.id in toDelete })
    }
}
