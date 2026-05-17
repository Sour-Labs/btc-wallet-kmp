package io.sourlabs.btc.wallet.keys

import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import io.sourlabs.btc.wallet.models.ScriptType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AddressConverterTest {

    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    @Test
    fun testP2PKHAddress() {
        val wallet = HDWalletManager.fromMnemonic(testMnemonic, "", Purpose.BIP44, Network.MAINNET)
        val converter = AddressConverter(Network.MAINNET)

        val pubKey = wallet.derivePublicKey(true, 0)
        val address = converter.toP2PKHAddress(pubKey)

        assertTrue(address.startsWith("1"), "P2PKH mainnet address should start with 1")
    }

    @Test
    fun testP2SHP2WPKHAddress() {
        val wallet = HDWalletManager.fromMnemonic(testMnemonic, "", Purpose.BIP49, Network.MAINNET)
        val converter = AddressConverter(Network.MAINNET)

        val pubKey = wallet.derivePublicKey(true, 0)
        val address = converter.toP2SHP2WPKHAddress(pubKey)

        assertTrue(address.startsWith("3"), "P2SH-P2WPKH mainnet address should start with 3")
    }

    @Test
    fun testP2WPKHAddress() {
        val wallet = HDWalletManager.fromMnemonic(testMnemonic, "", Purpose.BIP84, Network.MAINNET)
        val converter = AddressConverter(Network.MAINNET)

        val pubKey = wallet.derivePublicKey(true, 0)
        val address = converter.toP2WPKHAddress(pubKey)

        assertTrue(address.startsWith("bc1q"), "P2WPKH mainnet address should start with bc1q")
    }

    @Test
    fun testP2TRAddress() {
        val wallet = HDWalletManager.fromMnemonic(testMnemonic, "", Purpose.BIP86, Network.MAINNET)
        val converter = AddressConverter(Network.MAINNET)

        val pubKey = wallet.derivePublicKey(true, 0)
        val address = converter.toP2TRAddress(pubKey)

        assertTrue(address.startsWith("bc1p"), "P2TR mainnet address should start with bc1p")
    }

    @Test
    fun testTestnetAddresses() {
        val wallet = HDWalletManager.fromMnemonic(testMnemonic, "", Purpose.BIP84, Network.TESTNET)
        val converter = AddressConverter(Network.TESTNET)

        val pubKey = wallet.derivePublicKey(true, 0)
        val address = converter.toP2WPKHAddress(pubKey)

        assertTrue(address.startsWith("tb1q"), "P2WPKH testnet address should start with tb1q")
    }

    @Test
    fun testTestnet4P2WPKHAddress() {
        // TESTNET4 produces the same `tb1q…` bech32 prefix as TESTNET (Testnet3) —
        // both Testnet3 and Testnet4 use the same bech32 HRP. Anchors PR-12.
        val wallet = HDWalletManager.fromMnemonic(testMnemonic, "", Purpose.BIP84, Network.TESTNET4)
        val converter = AddressConverter(Network.TESTNET4)
        val address = converter.toP2WPKHAddress(wallet.derivePublicKey(true, 0))
        assertTrue(address.startsWith("tb1q"), "P2WPKH testnet4 address should start with tb1q, got $address")
    }

    @Test
    fun testAddressValidation() {
        val converter = AddressConverter(Network.MAINNET)

        // Valid addresses
        assertTrue(converter.isValidAddress("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))
        assertTrue(converter.isValidAddress("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"))
        assertTrue(converter.isValidAddress("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy"))

        // Invalid addresses
        assertTrue(!converter.isValidAddress("invalid"))
        assertTrue(!converter.isValidAddress(""))
    }

    @Test
    fun testAddressParsing() {
        val converter = AddressConverter(Network.MAINNET)

        val p2wpkhInfo = converter.parseAddress("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
        assertNotNull(p2wpkhInfo)
        assertEquals(ScriptType.P2WPKH, p2wpkhInfo.scriptType)

        val p2pkhInfo = converter.parseAddress("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2")
        assertNotNull(p2pkhInfo)
        assertEquals(ScriptType.P2PKH, p2pkhInfo.scriptType)
    }

    @Test
    fun parseAddressClassifiesP2SHAsGenericNotNestedSegWit() {
        // External `3...` addresses can wrap multisig, time-locked scripts, nested
        // SegWit, or anything else — the scriptPubKey alone (OP_HASH160 <h> OP_EQUAL)
        // doesn't reveal what the redeem script contains. The parser must report
        // ScriptType.P2SH (generic) rather than the wallet-specific P2SH_P2WPKH.
        val converter = AddressConverter(Network.MAINNET)
        val info = converter.parseAddress("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy")
        assertNotNull(info)
        assertEquals(ScriptType.P2SH, info.scriptType)
    }

    @Test
    fun toAddressRejectsGenericP2SH() {
        // P2SH addresses require a redeem script — a public key alone can't produce one.
        val converter = AddressConverter(Network.MAINNET)
        val wallet = HDWalletManager.fromMnemonic(testMnemonic, "", Purpose.BIP84, Network.MAINNET)
        val pubKey = wallet.derivePublicKey(true, 0)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            converter.toAddress(pubKey, ScriptType.P2SH)
        }
    }

    @Test
    fun createScriptPubKeyRejectsGenericP2SH() {
        val converter = AddressConverter(Network.MAINNET)
        val wallet = HDWalletManager.fromMnemonic(testMnemonic, "", Purpose.BIP84, Network.MAINNET)
        val pubKey = wallet.derivePublicKey(true, 0)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            converter.createScriptPubKey(pubKey, ScriptType.P2SH)
        }
    }

    @Test
    fun testToAddressWithScriptType() {
        val wallet = HDWalletManager.fromMnemonic(testMnemonic, "", Purpose.BIP84, Network.MAINNET)
        val converter = AddressConverter(Network.MAINNET)
        val pubKey = wallet.derivePublicKey(true, 0)

        val p2pkhAddr = converter.toAddress(pubKey, ScriptType.P2PKH)
        val p2shp2wpkhAddr = converter.toAddress(pubKey, ScriptType.P2SH_P2WPKH)
        val p2wpkhAddr = converter.toAddress(pubKey, ScriptType.P2WPKH)
        val p2trAddr = converter.toAddress(pubKey, ScriptType.P2TR)

        assertTrue(p2pkhAddr.startsWith("1"))
        assertTrue(p2shp2wpkhAddr.startsWith("3"))
        assertTrue(p2wpkhAddr.startsWith("bc1q"))
        assertTrue(p2trAddr.startsWith("bc1p"))
    }
}
