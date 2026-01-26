package io.sourlabs.btc.wallet.keys

import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HDWalletManagerTest {

    // Standard BIP39 test mnemonic
    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    @Test
    fun testMnemonicValidation() {
        assertTrue(HDWalletManager.validateMnemonic(testMnemonic))
    }

    @Test
    fun testMnemonicGeneration() {
        val mnemonic12 = HDWalletManager.generateMnemonic(12)
        assertEquals(12, mnemonic12.size)
        assertTrue(HDWalletManager.validateMnemonic(mnemonic12))

        val mnemonic24 = HDWalletManager.generateMnemonic(24)
        assertEquals(24, mnemonic24.size)
        assertTrue(HDWalletManager.validateMnemonic(mnemonic24))
    }

    @Test
    fun testWalletCreationFromMnemonic() {
        val wallet = HDWalletManager.fromMnemonic(
            mnemonic = testMnemonic,
            purpose = Purpose.BIP84,
            network = Network.MAINNET
        )

        assertEquals(Purpose.BIP84, wallet.purpose)
        assertEquals(Network.MAINNET, wallet.network)
        assertEquals(0, wallet.account)
        assertTrue(!wallet.isWatchOnly)
    }

    @Test
    fun testKeyDerivation() {
        val wallet = HDWalletManager.fromMnemonic(
            mnemonic = testMnemonic,
            purpose = Purpose.BIP84,
            network = Network.MAINNET
        )

        // Derive first external key
        val pubKey0 = wallet.derivePublicKey(isExternal = true, index = 0)
        assertNotNull(pubKey0)
        assertEquals(33, pubKey0.value.size()) // Compressed public key

        // Derive second external key - should be different
        val pubKey1 = wallet.derivePublicKey(isExternal = true, index = 1)
        assertNotNull(pubKey1)
        assertTrue(pubKey0 != pubKey1)

        // Derive change key
        val changeKey = wallet.derivePublicKey(isExternal = false, index = 0)
        assertNotNull(changeKey)
        assertTrue(pubKey0 != changeKey)
    }

    @Test
    fun testDerivationPath() {
        val wallet = HDWalletManager.fromMnemonic(
            mnemonic = testMnemonic,
            purpose = Purpose.BIP84,
            network = Network.MAINNET
        )

        val path = wallet.getDerivationPath(isExternal = true, index = 5)
        assertEquals("m/84'/0'/0'/0/5", path)

        val changePath = wallet.getDerivationPath(isExternal = false, index = 3)
        assertEquals("m/84'/0'/0'/1/3", changePath)
    }

    @Test
    fun testDifferentPurposes() {
        val bip44Wallet = HDWalletManager.fromMnemonic(testMnemonic, purpose = Purpose.BIP44)
        val bip49Wallet = HDWalletManager.fromMnemonic(testMnemonic, purpose = Purpose.BIP49)
        val bip84Wallet = HDWalletManager.fromMnemonic(testMnemonic, purpose = Purpose.BIP84)
        val bip86Wallet = HDWalletManager.fromMnemonic(testMnemonic, purpose = Purpose.BIP86)

        // All should derive different keys for the same index
        val key44 = bip44Wallet.derivePublicKey(true, 0)
        val key49 = bip49Wallet.derivePublicKey(true, 0)
        val key84 = bip84Wallet.derivePublicKey(true, 0)
        val key86 = bip86Wallet.derivePublicKey(true, 0)

        assertTrue(key44 != key49)
        assertTrue(key49 != key84)
        assertTrue(key84 != key86)
    }

    @Test
    fun testTestnetCoinType() {
        val mainnetWallet = HDWalletManager.fromMnemonic(testMnemonic, network = Network.MAINNET)
        val testnetWallet = HDWalletManager.fromMnemonic(testMnemonic, network = Network.TESTNET)

        val mainnetPath = mainnetWallet.getDerivationPath(true, 0)
        val testnetPath = testnetWallet.getDerivationPath(true, 0)

        assertTrue(mainnetPath.contains("/0'/")) // coin type 0
        assertTrue(testnetPath.contains("/1'/")) // coin type 1
    }
}
