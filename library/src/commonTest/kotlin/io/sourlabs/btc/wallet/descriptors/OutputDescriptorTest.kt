package io.sourlabs.btc.wallet.descriptors

import io.sourlabs.btc.wallet.api.DescriptorException
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class OutputDescriptorTest {

    private val mainnetXpub =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    private fun withChecksum(body: String): String =
        "$body#${DescriptorChecksum.compute(body)}"

    @Test
    fun dispatchesSingleKeyWpkh() {
        val parsed = OutputDescriptor.parse(withChecksum("wpkh($mainnetXpub/0/*)"))
        val single = assertIs<OutputDescriptor.SingleKey>(parsed)
        assertIs<Descriptor.Wpkh>(single.descriptor)
        assertEquals(Purpose.BIP84, parsed.purpose)
        assertEquals(Network.MAINNET, parsed.network)
    }

    @Test
    fun dispatchesSingleKeyPkh() {
        val parsed = OutputDescriptor.parse(withChecksum("pkh($mainnetXpub/0/*)"))
        val single = assertIs<OutputDescriptor.SingleKey>(parsed)
        assertIs<Descriptor.Pkh>(single.descriptor)
    }

    @Test
    fun dispatchesSingleKeyShWpkh() {
        val parsed = OutputDescriptor.parse(withChecksum("sh(wpkh($mainnetXpub/0/*))"))
        val single = assertIs<OutputDescriptor.SingleKey>(parsed)
        assertIs<Descriptor.ShWpkh>(single.descriptor)
    }

    @Test
    fun dispatchesSingleKeyTr() {
        val parsed = OutputDescriptor.parse(withChecksum("tr($mainnetXpub/0/*)"))
        val single = assertIs<OutputDescriptor.SingleKey>(parsed)
        assertIs<Descriptor.Tr>(single.descriptor)
    }

    @Test
    fun dispatchesMultisigWsh() {
        val body = "wsh(sortedmulti(2,$mainnetXpub/0/*,$mainnetXpub/0/*))"
        val parsed = OutputDescriptor.parse(withChecksum(body))
        val multi = assertIs<OutputDescriptor.Multisig>(parsed)
        assertIs<MultisigDescriptor.WshSortedMulti>(multi.descriptor)
        assertEquals(Purpose.BIP84, parsed.purpose)
        assertEquals(Network.MAINNET, parsed.network)
    }

    @Test
    fun rejectsUnknownWrapper() {
        assertFailsWith<DescriptorException.Unsupported> {
            OutputDescriptor.parse(withChecksum("combo($mainnetXpub)"))
        }
    }

    @Test
    fun rejectsMalformedTopLevel() {
        assertFailsWith<DescriptorException.Malformed> {
            OutputDescriptor.parse("noparenthesis")
        }
    }

    @Test
    fun preservesChecksumValidationOnSingleKeyPath() {
        assertFailsWith<DescriptorException.InvalidChecksum> {
            OutputDescriptor.parse("wpkh($mainnetXpub/0/*)")
        }
    }

    @Test
    fun preservesChecksumValidationOnMultisigPath() {
        assertFailsWith<DescriptorException.InvalidChecksum> {
            OutputDescriptor.parse("wsh(sortedmulti(2,$mainnetXpub/0/*,$mainnetXpub/0/*))")
        }
    }

    @Test
    fun trimsWhitespace() {
        val parsed = OutputDescriptor.parse("  " + withChecksum("wpkh($mainnetXpub/0/*)") + "  \n")
        assertIs<OutputDescriptor.SingleKey>(parsed)
    }
}
