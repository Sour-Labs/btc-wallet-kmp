package io.sourlabs.btc.wallet.keys

import fr.acinq.bitcoin.KeyPath
import io.sourlabs.btc.wallet.keys.SeedManager.toSeed
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KeyDerivationTest {

    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    private val seed: ByteArray = testMnemonic.toSeed()

    @Test
    fun derivationIsDeterministic() {
        val k1 = KeyDerivation.derivePrivateKeyAtPath(seed, "m/2026'/0'/0'")
        val k2 = KeyDerivation.derivePrivateKeyAtPath(seed, "m/2026'/0'/0'")
        assertEquals(k1, k2)
    }

    @Test
    fun differentPathsProduceDifferentKeys() {
        val k1 = KeyDerivation.derivePrivateKeyAtPath(seed, "m/2026'/0'/0'")
        val k2 = KeyDerivation.derivePrivateKeyAtPath(seed, "m/2026'/0'/1'")
        assertNotEquals(k1, k2)
    }

    @Test
    fun masterPathReturnsMasterKey() {
        val master = KeyDerivation.derivePrivateKeyAtPath(seed, "m")
        assertEquals(0, master.depth)
        assertEquals(KeyPath.empty, master.path)
    }

    @Test
    fun customPathProducesCompressedPubkey() {
        val k = KeyDerivation.derivePrivateKeyAtPath(seed, "m/2026'/0'/0'")
        val pubKey = k.publicKey
        assertEquals(33, pubKey.value.size())
        assertTrue(pubKey.value[0] == 0x02.toByte() || pubKey.value[0] == 0x03.toByte())
    }

    @Test
    fun stringAndKeyPathOverloadsMatch() {
        val viaString = KeyDerivation.derivePrivateKeyAtPath(seed, "m/2026'/0'/0'")
        val viaKeyPath = KeyDerivation.derivePrivateKeyAtPath(seed, KeyPath.fromPath("m/2026'/0'/0'"))
        assertEquals(viaString, viaKeyPath)
    }

    @Test
    fun apostropheAndHHardenedMarkersAreEquivalent() {
        val viaApostrophe = KeyDerivation.derivePrivateKeyAtPath(seed, "m/2026'/0'/0'")
        val viaH = KeyDerivation.derivePrivateKeyAtPath(seed, "m/2026h/0h/0h")
        assertEquals(viaApostrophe, viaH)
    }

    @Test
    fun matchesHDWalletManagerForBip84Path() {
        val wallet = HDWalletManager.fromMnemonic(
            mnemonic = testMnemonic,
            purpose = Purpose.BIP84,
            network = Network.MAINNET,
        )
        val viaWalletManager = wallet.derivePrivateKey(isExternal = true, index = 0).privateKey

        val viaKeyDerivation = KeyDerivation
            .derivePrivateKeyAtPath(seed, "m/84'/0'/0'/0/0")
            .privateKey

        assertEquals(viaWalletManager, viaKeyDerivation)
    }

    @Test
    fun malformedPathThrows() {
        assertFailsWith<NumberFormatException> {
            KeyDerivation.derivePrivateKeyAtPath(seed, "m/not-a-number/0")
        }
    }
}
