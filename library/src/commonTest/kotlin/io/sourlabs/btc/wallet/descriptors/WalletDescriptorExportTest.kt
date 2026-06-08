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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [WalletDescriptorExport]. Anchored on the BIP-84 published vector
 * (mnemonic = abandon × 11 + about → m/84'/0'/0'/0/0 =
 * bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu) so correctness is tied to a known
 * address, not to the export's own assembly logic.
 */
class WalletDescriptorExportTest {

    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about",
    )
    private val expectedFirstReceiveBip84 = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"

    @Test
    fun mnemonicExportHasOriginCoversBothBranchesAndRoundTrips() {
        val config = WalletConfig.FromMnemonic(
            mnemonic = testMnemonic,
            purpose = Purpose.BIP84,
            network = Network.MAINNET,
        )

        val descriptor = WalletDescriptorExport.outputDescriptor(config)

        // Exact form: wpkh([<masterFp>/84h/0h/0h]<xpub>/<0;1>/*)#<checksum>.
        val xpub = accountXpub(testMnemonic, Purpose.BIP84, Network.MAINNET)
        val body = "wpkh([${masterFingerprintHex(testMnemonic)}/84h/0h/0h]$xpub/<0;1>/*)"
        assertEquals("$body#${DescriptorChecksum.compute(body)}", descriptor)

        // Round-trips through our own importer …
        val parsed = OutputDescriptor.parse(descriptor)
        assertIs<OutputDescriptor.SingleKey>(parsed)
        assertEquals(Purpose.BIP84, parsed.purpose)

        // … and the re-imported wallet derives the published BIP-84 address.
        assertEquals(expectedFirstReceiveBip84, firstReceiveAddress(descriptor))
    }

    @Test
    fun bareXpubExportIsOriginLessAndRoundTrips() {
        val xpub = accountXpub(testMnemonic, Purpose.BIP84, Network.MAINNET)
        val config = WalletConfig.WatchOnly(
            extendedPublicKey = xpub,
            purpose = Purpose.BIP84,
            network = Network.MAINNET,
        )

        val descriptor = WalletDescriptorExport.outputDescriptor(config)

        val body = "wpkh($xpub/<0;1>/*)"
        assertEquals("$body#${DescriptorChecksum.compute(body)}", descriptor)
        assertFalse(descriptor.contains('['), "bare-xpub export must carry no key origin")
        assertEquals(expectedFirstReceiveBip84, firstReceiveAddress(descriptor))
    }

    @Test
    fun descriptorConfigIsReturnedVerbatim() {
        val singleKeyBody = "wpkh(${accountXpub(testMnemonic, Purpose.BIP84, Network.MAINNET)}/<0;1>/*)"
        val singleKey = "$singleKeyBody#${DescriptorChecksum.compute(singleKeyBody)}"
        assertEquals(
            singleKey,
            WalletDescriptorExport.outputDescriptor(WalletConfig.WatchOnlyDescriptor(singleKey)),
        )

        val multisigBody = "wsh(sortedmulti(2," +
            "[151c0436/84'/0'/0']$BITKEY_KEY1/0/*," +
            "[d0ec9d96/84'/0'/0']$BITKEY_KEY2/0/*," +
            "[c6f75db6/84'/0'/0']$BITKEY_KEY3/0/*" +
            "))"
        val multisig = "$multisigBody#${DescriptorChecksum.compute(multisigBody)}"
        assertEquals(
            multisig,
            WalletDescriptorExport.outputDescriptor(WalletConfig.WatchOnlyDescriptor(multisig)),
        )
    }

    @Test
    fun wrapperMatchesPurposeAndRoundTrips() {
        fun export(purpose: Purpose) = WalletDescriptorExport.outputDescriptor(
            WalletConfig.FromMnemonic(testMnemonic, purpose = purpose, network = Network.MAINNET),
        )
        assertTrue(export(Purpose.BIP44).startsWith("pkh("))
        assertTrue(export(Purpose.BIP49).startsWith("sh(wpkh("))
        assertTrue(export(Purpose.BIP84).startsWith("wpkh("))
        assertTrue(export(Purpose.BIP86).startsWith("tr("))
        // Every wrapper must still parse back through our importer.
        Purpose.entries.forEach { OutputDescriptor.parse(export(it)) }
    }

    @Test
    fun testnetMnemonicExportUsesTpub() {
        val descriptor = WalletDescriptorExport.outputDescriptor(
            WalletConfig.FromMnemonic(testMnemonic, purpose = Purpose.BIP84, network = Network.TESTNET),
        )
        // Origin coin type is 1 for testnet, and the key re-encodes as a tpub.
        assertTrue(descriptor.contains("/84h/1h/0h]"), descriptor)
        assertTrue(descriptor.contains("]tpub"), descriptor)
    }

    // --- helpers (mirror DescriptorIntegrationTest, anchored to ACINQ) ---

    private fun firstReceiveAddress(descriptor: String): String {
        val wallet = HDWalletManager.fromConfig(WalletConfig.WatchOnlyDescriptor(descriptor))
        val key = wallet.derivePublicKey(isExternal = true, index = 0)
        return AddressConverter(Network.MAINNET).toP2WPKHAddress(key)
    }

    private fun accountXpub(mnemonic: List<String>, purpose: Purpose, network: Network): String {
        val master = DeterministicWallet.generate(mnemonic.toSeed())
        val purposeKey = DeterministicWallet.derivePrivateKey(
            master, DeterministicWallet.hardened(purpose.value.toLong()),
        )
        val coinKey = DeterministicWallet.derivePrivateKey(
            purposeKey, DeterministicWallet.hardened(network.coinType.toLong()),
        )
        val accountKey = DeterministicWallet.derivePrivateKey(
            coinKey, DeterministicWallet.hardened(0L),
        )
        return DeterministicWallet.publicKey(accountKey).encode(testnet = network != Network.MAINNET)
    }

    private fun masterFingerprintHex(mnemonic: List<String>): String {
        val master = DeterministicWallet.generate(mnemonic.toSeed())
        val fp = DeterministicWallet.publicKey(master).fingerprint() and 0xffffffffL
        return fp.toString(16).padStart(8, '0')
    }

    private companion object {
        // Bitkey 2-of-3 watch-only cosigner xpubs (no funds) reused from
        // MultisigDescriptorParserTest as a valid multisig passthrough vector.
        const val BITKEY_KEY1 =
            "xpub6CKrVEyoK68fJ5WnniiFoiQCsXa32rnM7rHXoiFZji1g9nJdgWtydoXfWQeGSt4LVjwptLQTnZfdKV2b37Ux5asMBfDXt2KBzFJKU2i5vfr"
        const val BITKEY_KEY2 =
            "xpub6D6DBgdzpTbAL4Ln4ufFiYdiRKRKg1cMgDtzYGHSmyuHxvUBqkfjQsc7rDc1y66i1v5KUKLSNU6AbG69kEQDNbyGY5KZ6tZL9dFg8to9Ekc"
        const val BITKEY_KEY3 =
            "xpub6Cv5iQBhcq7buXp5VbrAzzH3tPNTDCvzLFkFVhrc4Ybet8EHH6oSs16UuD2FMmAHQn7TiuCVtBuPNz7rCjsg9pdEKBo7aCUAiMGFbt9W387"
    }
}
