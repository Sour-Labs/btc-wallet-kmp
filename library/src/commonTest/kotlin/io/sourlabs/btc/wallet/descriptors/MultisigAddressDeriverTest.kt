package io.sourlabs.btc.wallet.descriptors

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Script
import io.sourlabs.btc.wallet.models.Network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MultisigAddressDeriverTest {

    // BIP-84 zpub from the canonical "abandon...about" mnemonic — used everywhere
    // in the existing descriptor tests as a stable, public test fixture.
    private val mainnetXpubA =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    // Independent test xpub (Bitkey export key 2). Mainnet account-level.
    private val mainnetXpubB =
        "xpub6D6DBgdzpTbAL4Ln4ufFiYdiRKRKg1cMgDtzYGHSmyuHxvUBqkfjQsc7rDc1y66i1v5KUKLSNU6AbG69kEQDNbyGY5KZ6tZL9dFg8to9Ekc"

    // Independent test xpub (Bitkey export key 3). Mainnet account-level.
    private val mainnetXpubC =
        "xpub6Cv5iQBhcq7buXp5VbrAzzH3tPNTDCvzLFkFVhrc4Ybet8EHH6oSs16UuD2FMmAHQn7TiuCVtBuPNz7rCjsg9pdEKBo7aCUAiMGFbt9W387"

    // Derived at test runtime from the canonical "abandon...about" mnemonic at
    // BIP-84 testnet path m/84'/1'/0'. We can't use a fixed string here because
    // the deriver actually Base58Check-decodes the xpub; the synthetic tpub the
    // parser tests use isn't a valid Base58Check string.
    private val testnetXpub: String by lazy {
        val seed = MnemonicCode.toSeed(List(11) { "abandon" } + "about", passphrase = "")
        val master = DeterministicWallet.generate(ByteVector(seed))
        val accountKey = DeterministicWallet.derivePrivateKey(master, "84'/1'/0'")
        DeterministicWallet.publicKey(accountKey).encode(testnet = true)
    }

    /**
     * Build a [MultisigDescriptor.WshSortedMulti] without going through the
     * descriptor string parser — useful for tests that want to control key
     * order or use synthetic xpubs.
     */
    private fun sortedMulti(
        threshold: Int,
        xpubs: List<String>,
        network: Network = Network.MAINNET,
    ): MultisigDescriptor.WshSortedMulti =
        MultisigDescriptor.WshSortedMulti(
            threshold = threshold,
            keys = xpubs.map { MultisigDescriptor.Key(keyOrigin = null, extendedPublicKey = it) },
            network = network,
        )

    @Test
    fun derivesValidBech32P2wshAddress() {
        val descriptor = sortedMulti(2, listOf(mainnetXpubA, mainnetXpubB, mainnetXpubC))
        val address = MultisigAddressDeriver.derive(descriptor, change = false, index = 0)
        // P2WSH on mainnet: bech32, HRP `bc`, witness program is 32 bytes,
        // total length 62 characters (bc1q + 58 chars of bech32 data).
        assertTrue(address.startsWith("bc1q"), "expected mainnet P2WSH prefix, got '$address'")
        assertEquals(62, address.length, "expected 62-char P2WSH bech32 address, got '$address'")
    }

    @Test
    fun derivesValidTestnetBech32Address() {
        val descriptor = sortedMulti(1, listOf(testnetXpub), network = Network.TESTNET)
        val address = MultisigAddressDeriver.derive(descriptor, change = false, index = 0)
        assertTrue(address.startsWith("tb1q"), "expected testnet P2WSH prefix, got '$address'")
        assertEquals(62, address.length)
    }

    @Test
    fun sortedMultiAddressIsIndependentOfKeyOrder() {
        // BIP-67: sortedmulti sorts derived pubkeys lexicographically. Two
        // descriptors that list the same cosigner xpubs in different orders
        // must produce the same address.
        val orderAbc = sortedMulti(2, listOf(mainnetXpubA, mainnetXpubB, mainnetXpubC))
        val orderCab = sortedMulti(2, listOf(mainnetXpubC, mainnetXpubA, mainnetXpubB))
        val orderBca = sortedMulti(2, listOf(mainnetXpubB, mainnetXpubC, mainnetXpubA))

        val addrAbc = MultisigAddressDeriver.derive(orderAbc, change = false, index = 0)
        val addrCab = MultisigAddressDeriver.derive(orderCab, change = false, index = 0)
        val addrBca = MultisigAddressDeriver.derive(orderBca, change = false, index = 0)

        assertEquals(addrAbc, addrCab)
        assertEquals(addrAbc, addrBca)
    }

    @Test
    fun receiveAndChangeProduceDifferentAddresses() {
        val descriptor = sortedMulti(2, listOf(mainnetXpubA, mainnetXpubB))
        val receive = MultisigAddressDeriver.derive(descriptor, change = false, index = 0)
        val change = MultisigAddressDeriver.derive(descriptor, change = true, index = 0)
        assertNotEquals(receive, change)
    }

    @Test
    fun differentIndicesProduceDifferentAddresses() {
        val descriptor = sortedMulti(2, listOf(mainnetXpubA, mainnetXpubB))
        val addr0 = MultisigAddressDeriver.derive(descriptor, change = false, index = 0)
        val addr1 = MultisigAddressDeriver.derive(descriptor, change = false, index = 1)
        val addr2 = MultisigAddressDeriver.derive(descriptor, change = false, index = 2)
        // All three must be distinct — gap-limit scanning relies on this.
        assertNotEquals(addr0, addr1)
        assertNotEquals(addr1, addr2)
        assertNotEquals(addr0, addr2)
    }

    @Test
    fun derivedAddressMatchesIndependentAcinqDerivation() {
        // Cross-check the deriver against ACINQ's primitives composed manually.
        // This verifies the integration glue (BIP-67 sort + OP_M assembly +
        // P2WSH wrapping + bech32 encoding) without trusting the deriver itself.
        val descriptor = sortedMulti(2, listOf(mainnetXpubA, mainnetXpubB, mainnetXpubC))
        val deriverAddress =
            MultisigAddressDeriver.derive(descriptor, change = false, index = 0)

        val pubkeys = listOf(mainnetXpubA, mainnetXpubB, mainnetXpubC).map { xpub ->
            val parent = DeterministicWallet.ExtendedPublicKey.decode(xpub).second
            val chainKey = DeterministicWallet.derivePublicKey(parent, 0L)
            DeterministicWallet.derivePublicKey(chainKey, 0L).publicKey
        }
        val sorted = pubkeys.sortedWith(byteLexComparator())
        val redeem = Script.createMultiSigMofN(2, sorted)
        val p2wsh = Script.pay2wsh(redeem)
        val expected = Bitcoin.addressFromPublicKeyScript(Chain.Mainnet.chainHash, p2wsh)
        assertTrue(expected.isRight, "ACINQ failed to encode P2WSH address")
        @Suppress("UNCHECKED_CAST")
        val expectedAddress =
            (expected as fr.acinq.bitcoin.utils.Either.Right<String>).value

        assertEquals(expectedAddress, deriverAddress)
    }

    @Test
    fun rejectsNegativeIndex() {
        val descriptor = sortedMulti(2, listOf(mainnetXpubA, mainnetXpubB))
        assertFailsWithMessage("index must be non-negative") {
            MultisigAddressDeriver.derive(descriptor, change = false, index = -1)
        }
    }

    @Test
    fun parsingAndDerivingAreInverseOfTheRoundTrip() {
        // Build a descriptor by parsing a string, derive an address, and ensure
        // the same address comes out when we construct the equivalent
        // WshSortedMulti directly. Catches any drift between the parser's
        // internal representation and the deriver's expectations.
        val body =
            "wsh(sortedmulti(2,$mainnetXpubA/0/*,$mainnetXpubB/0/*,$mainnetXpubC/0/*))"
        val checksum = DescriptorChecksum.compute(body)
        val parsed = MultisigDescriptor.parse("$body#$checksum")

        val fromParse = MultisigAddressDeriver.derive(parsed, change = false, index = 0)
        val fromManual = MultisigAddressDeriver.derive(
            sortedMulti(2, listOf(mainnetXpubA, mainnetXpubB, mainnetXpubC)),
            change = false,
            index = 0,
        )
        assertEquals(fromManual, fromParse)
    }

    private fun byteLexComparator(): Comparator<PublicKey> = Comparator { a, b ->
        val aBytes = a.value.toByteArray()
        val bBytes = b.value.toByteArray()
        val minLen = minOf(aBytes.size, bBytes.size)
        for (i in 0 until minLen) {
            val cmp = (aBytes[i].toInt() and 0xff) - (bBytes[i].toInt() and 0xff)
            if (cmp != 0) return@Comparator cmp
        }
        aBytes.size - bBytes.size
    }

    private inline fun assertFailsWithMessage(
        expectedSubstring: String,
        block: () -> Unit,
    ) {
        try {
            block()
            throw AssertionError("expected exception with message containing '$expectedSubstring' but none was thrown")
        } catch (e: IllegalArgumentException) {
            val msg = e.message ?: ""
            if (!msg.contains(expectedSubstring)) {
                throw AssertionError("expected '$expectedSubstring' in '$msg'", e)
            }
        }
    }
}
