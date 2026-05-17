package io.sourlabs.btc.wallet.keys

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.DeterministicWallet
import io.sourlabs.btc.wallet.api.WalletInitializationException
import io.sourlabs.btc.wallet.keys.SeedManager.toSeed
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HDWalletManagerTest {

    // Standard BIP39 test mnemonic
    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    /** Build the account-level xprv at m/84'/0'/0' for the test mnemonic. */
    private fun accountXprvFor(purpose: Purpose, network: Network, account: Int = 0): String {
        val master = DeterministicWallet.generate(ByteVector(testMnemonic.toSeed()))
        val purposeKey = DeterministicWallet.derivePrivateKey(
            master, DeterministicWallet.hardened(purpose.value.toLong())
        )
        val coinKey = DeterministicWallet.derivePrivateKey(
            purposeKey, DeterministicWallet.hardened(network.coinType.toLong())
        )
        val accountKey = DeterministicWallet.derivePrivateKey(
            coinKey, DeterministicWallet.hardened(account.toLong())
        )
        return accountKey.encode(testnet = network != Network.MAINNET)
    }

    /** Encode the master xprv (depth 0) — used to anchor the rejection test. */
    private fun masterXprv(): String {
        val master = DeterministicWallet.generate(ByteVector(testMnemonic.toSeed()))
        return master.encode(testnet = false)
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

    @Test
    fun fromExtendedPrivateKeyRejectsMasterDepth() {
        // Master xprvs are at BIP-32 depth 0. The library refuses them because
        // it can't tell whether the caller meant "use this as my account key
        // somehow" or "this is the master, please derive the account from it"
        // — the answers produce two different wallets, silently.
        val ex = assertFailsWith<WalletInitializationException> {
            HDWalletManager.fromExtendedPrivateKey(
                accountExtendedPrivateKey = masterXprv(),
                purpose = Purpose.BIP84,
                network = Network.MAINNET,
            )
        }
        assertTrue(
            ex.message!!.contains("depth 3"),
            "error should mention the expected depth: was ${ex.message}",
        )
        assertTrue(
            ex.message!!.contains("Got depth 0"),
            "error should name the actual depth: was ${ex.message}",
        )
    }

    @Test
    fun fromExtendedPrivateKeyAtAccountDepthMatchesFromMnemonic() {
        // The point of the account-level convention: if a caller exports the
        // account xprv from a hardware wallet and passes it here, the resulting
        // wallet must derive the same external/internal keys as the mnemonic-
        // backed wallet for the same seed.
        val accountXprv = accountXprvFor(Purpose.BIP84, Network.MAINNET)
        val fromXprv = HDWalletManager.fromExtendedPrivateKey(
            accountExtendedPrivateKey = accountXprv,
            purpose = Purpose.BIP84,
            network = Network.MAINNET,
        )
        val fromMnemonic = HDWalletManager.fromMnemonic(
            mnemonic = testMnemonic,
            purpose = Purpose.BIP84,
            network = Network.MAINNET,
        )
        for (i in 0..3) {
            assertEquals(
                fromMnemonic.derivePublicKey(true, i),
                fromXprv.derivePublicKey(true, i),
                "external key mismatch at index $i",
            )
            assertEquals(
                fromMnemonic.derivePrivateKey(true, i).privateKey,
                fromXprv.derivePrivateKey(true, i).privateKey,
                "external private key mismatch at index $i",
            )
        }
    }

    @Test
    fun testnet4DerivesSameKeysAsTestnet3() {
        // TESTNET4 shares BIP-44 coin type 1 with TESTNET (Testnet3), so HD
        // derivation paths are identical — only the underlying chain differs.
        // Anchors PR-12's TESTNET4 addition.
        val testnetWallet = HDWalletManager.fromMnemonic(
            testMnemonic, purpose = Purpose.BIP84, network = Network.TESTNET,
        )
        val testnet4Wallet = HDWalletManager.fromMnemonic(
            testMnemonic, purpose = Purpose.BIP84, network = Network.TESTNET4,
        )
        for (i in 0..3) {
            assertEquals(
                testnetWallet.derivePublicKey(true, i),
                testnet4Wallet.derivePublicKey(true, i),
                "TESTNET4 should derive identical external keys to TESTNET at index $i",
            )
            assertEquals(
                testnetWallet.derivePrivateKey(true, i).privateKey,
                testnet4Wallet.derivePrivateKey(true, i).privateKey,
                "TESTNET4 should derive identical external private keys to TESTNET at index $i",
            )
        }
    }

    @Test
    fun fromExtendedPublicKeyRejectsMasterDepth() {
        // Master xpubs are also depth 0; same rationale as the xprv case.
        val masterXpub = DeterministicWallet.publicKey(
            DeterministicWallet.generate(ByteVector(testMnemonic.toSeed()))
        ).encode(testnet = false)
        val ex = assertFailsWith<WalletInitializationException> {
            HDWalletManager.fromExtendedPublicKey(
                extendedPublicKey = masterXpub,
                purpose = Purpose.BIP84,
                network = Network.MAINNET,
            )
        }
        assertTrue(ex.message!!.contains("depth 3"))
    }
}
