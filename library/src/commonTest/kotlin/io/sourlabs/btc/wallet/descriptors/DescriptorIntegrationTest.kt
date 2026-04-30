package io.sourlabs.btc.wallet.descriptors

import fr.acinq.bitcoin.DeterministicWallet
import io.sourlabs.btc.wallet.core.WalletConfig
import io.sourlabs.btc.wallet.keys.AddressConverter
import io.sourlabs.btc.wallet.keys.HDWalletManager
import io.sourlabs.btc.wallet.keys.SeedManager.toSeed
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end tests: descriptor → HDWalletManager → first receive address.
 *
 * Anchored on the BIP-84 test vector
 * (https://github.com/bitcoin/bips/blob/master/bip-0084.mediawiki):
 *
 *   mnemonic = abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about
 *   m/84'/0'/0'/0/0 → bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu
 */
class DescriptorIntegrationTest {

    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about",
    )

    private val expectedFirstReceiveBip84 = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"

    /**
     * Build a wpkh descriptor wrapping the account xpub for the BIP-84
     * mnemonic, parse it, and verify both the parser and the resulting
     * HDWalletManager derive the BIP-84 published first receive address.
     */
    @Test
    fun wpkhDescriptorMatchesBip84FirstReceiveAddress() {
        val accountXpub = accountXpub(testMnemonic, Purpose.BIP84, Network.MAINNET)
        val fpHex = masterFingerprintHex(testMnemonic)

        val body = "wpkh([$fpHex/84h/0h/0h]$accountXpub/0/*)"
        val descriptor = "$body#${DescriptorChecksum.compute(body)}"

        val parsed = Descriptor.parse(descriptor)
        assertEquals(Purpose.BIP84, parsed.purpose)
        assertEquals(Network.MAINNET, parsed.network)
        assertEquals(accountXpub, parsed.extendedPublicKey)
        assertEquals(0, parsed.account)

        val wallet = HDWalletManager.fromDescriptor(parsed)
        val firstReceiveKey = wallet.derivePublicKey(isExternal = true, index = 0)
        val firstReceiveAddress = AddressConverter(Network.MAINNET).toP2WPKHAddress(firstReceiveKey)
        assertEquals(expectedFirstReceiveBip84, firstReceiveAddress)
    }

    /**
     * Routing the same descriptor through WalletConfig.WatchOnlyDescriptor
     * (i.e. the public-API entry point) lands on the same address.
     */
    @Test
    fun watchOnlyDescriptorConfigEndToEnd() {
        val accountXpub = accountXpub(testMnemonic, Purpose.BIP84, Network.MAINNET)
        val fpHex = masterFingerprintHex(testMnemonic)
        val body = "wpkh([$fpHex/84h/0h/0h]$accountXpub/0/*)"
        val descriptor = "$body#${DescriptorChecksum.compute(body)}"

        val config = WalletConfig.WatchOnlyDescriptor(descriptor = descriptor)
        assertEquals(Purpose.BIP84, config.purpose)
        assertEquals(Network.MAINNET, config.network)
        assertEquals(0, config.account)

        val wallet = HDWalletManager.fromConfig(config)
        val firstReceiveKey = wallet.derivePublicKey(isExternal = true, index = 0)
        val firstReceiveAddress = AddressConverter(Network.MAINNET).toP2WPKHAddress(firstReceiveKey)
        assertEquals(expectedFirstReceiveBip84, firstReceiveAddress)
    }

    /**
     * Confirms the descriptor pipeline produces the same first-receive key as
     * feeding the same xpub through the existing fromExtendedPublicKey path
     * with explicit purpose. This shows the descriptor wrapper correctly
     * dictates the BIP purpose for downstream address derivation.
     */
    @Test
    fun descriptorAndBareXpubProduceSameAddress() {
        val accountXpub = accountXpub(testMnemonic, Purpose.BIP84, Network.MAINNET)

        val viaBareXpub = HDWalletManager.fromExtendedPublicKey(
            extendedPublicKey = accountXpub,
            purpose = Purpose.BIP84,
            network = Network.MAINNET,
        )
        val viaDescriptor = HDWalletManager.fromDescriptor(
            "wpkh($accountXpub/0/*)" + "#" + DescriptorChecksum.compute("wpkh($accountXpub/0/*)")
        )

        val converter = AddressConverter(Network.MAINNET)
        for (i in 0..5) {
            assertEquals(
                converter.toP2WPKHAddress(viaBareXpub.derivePublicKey(true, i)),
                converter.toP2WPKHAddress(viaDescriptor.derivePublicKey(true, i)),
                "address mismatch at index $i",
            )
        }
    }

    /** Build the account-level extended public key at m/purpose'/coin'/account'. */
    private fun accountXpub(mnemonic: List<String>, purpose: Purpose, network: Network): String {
        val seed = mnemonic.toSeed()
        val master = DeterministicWallet.generate(seed)
        val purposeKey = DeterministicWallet.derivePrivateKey(
            master, DeterministicWallet.hardened(purpose.value.toLong())
        )
        val coinKey = DeterministicWallet.derivePrivateKey(
            purposeKey, DeterministicWallet.hardened(network.coinType.toLong())
        )
        val accountKey = DeterministicWallet.derivePrivateKey(
            coinKey, DeterministicWallet.hardened(0L)
        )
        return DeterministicWallet.publicKey(accountKey).encode(testnet = network != Network.MAINNET)
    }

    /** Format the master public key fingerprint as 8 hex characters. */
    private fun masterFingerprintHex(mnemonic: List<String>): String {
        val seed = mnemonic.toSeed()
        val master = DeterministicWallet.generate(seed)
        val masterPub = DeterministicWallet.publicKey(master)
        val fp = masterPub.fingerprint() and 0xffffffffL
        return fp.toString(16).padStart(8, '0')
    }
}
