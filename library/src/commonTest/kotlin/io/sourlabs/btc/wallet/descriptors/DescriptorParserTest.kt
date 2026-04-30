package io.sourlabs.btc.wallet.descriptors

import io.sourlabs.btc.wallet.api.DescriptorException
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DescriptorParserTest {

    // BIP-84 published account-level zpub for the "abandon...about" mnemonic (mainnet, m/84'/0'/0').
    // https://github.com/bitcoin/bips/blob/master/bip-0084.mediawiki
    private val mainnetAccountKey =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    // A representative testnet tpub. The parser doesn't validate Base58Check; it only inspects the prefix.
    private val testnetAccountKey =
        "tpubDCsfvDP3W6XZUNvW6FDoCLqWgvD2BkpsK6foLBcETtm6Pde9SwoxRA1eaLA1WW6mDDuxYHb2NQfbHWxxupTtUdYBjJVBpejP1NMSJTrYsdW"

    private fun withChecksum(body: String): String {
        val checksum = DescriptorChecksum.compute(body)
        return "$body#$checksum"
    }

    @Test
    fun parsesBareWpkhMainnet() {
        val descriptor = withChecksum("wpkh($mainnetAccountKey/0/*)")
        val parsed = Descriptor.parse(descriptor)
        assertIs<Descriptor.Wpkh>(parsed)
        assertEquals(Purpose.BIP84, parsed.purpose)
        assertEquals(Network.MAINNET, parsed.network)
        assertEquals(mainnetAccountKey, parsed.extendedPublicKey)
        assertNull(parsed.keyOrigin)
        assertEquals(0, parsed.account)
    }

    @Test
    fun parsesWpkhWithKeyOrigin() {
        val body = "wpkh([deadbeef/84h/0h/0h]$mainnetAccountKey/0/*)"
        val parsed = Descriptor.parse(withChecksum(body))
        assertIs<Descriptor.Wpkh>(parsed)
        val origin = assertNotNull(parsed.keyOrigin)
        assertEquals(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()).toList(),
                     origin.fingerprint.toList())
        assertEquals(3, origin.path.size)
        assertEquals(Descriptor.PathStep(84, true), origin.path[0])
        assertEquals(Descriptor.PathStep(0, true), origin.path[1])
        assertEquals(Descriptor.PathStep(0, true), origin.path[2])
        assertEquals(0, parsed.account)
    }

    @Test
    fun parsesWpkhWithAccountIndex2() {
        val body = "wpkh([deadbeef/84h/0h/2h]$mainnetAccountKey/0/*)"
        val parsed = Descriptor.parse(withChecksum(body))
        assertEquals(2, parsed.account)
    }

    @Test
    fun parsesWpkhMultipathSuffix() {
        val parsed = Descriptor.parse(withChecksum("wpkh($mainnetAccountKey/<0;1>/*)"))
        assertIs<Descriptor.Wpkh>(parsed)
    }

    @Test
    fun parsesWpkhAlternativeMultipathSuffix() {
        val parsed = Descriptor.parse(withChecksum("wpkh($mainnetAccountKey/{0,1}/*)"))
        assertIs<Descriptor.Wpkh>(parsed)
    }

    @Test
    fun parsesWpkhNoSuffix() {
        // A bare account-level xpub without a derivation suffix. We treat this
        // as an account key and walk /chain/index downstream.
        val parsed = Descriptor.parse(withChecksum("wpkh($mainnetAccountKey)"))
        assertIs<Descriptor.Wpkh>(parsed)
    }

    @Test
    fun acceptsApostropheHardened() {
        // BIP-380 allows ' or h interchangeably for hardened steps.
        val parsed = Descriptor.parse(
            withChecksum("wpkh([deadbeef/84'/0'/0']$mainnetAccountKey/0/*)")
        )
        val origin = assertNotNull(parsed.keyOrigin)
        assertTrue(origin.path.all { it.hardened })
    }

    @Test
    fun parsesPkhMainnet() {
        val parsed = Descriptor.parse(withChecksum("pkh($mainnetAccountKey/0/*)"))
        assertIs<Descriptor.Pkh>(parsed)
        assertEquals(Purpose.BIP44, parsed.purpose)
    }

    @Test
    fun parsesShWpkhMainnet() {
        val parsed = Descriptor.parse(withChecksum("sh(wpkh($mainnetAccountKey/0/*))"))
        assertIs<Descriptor.ShWpkh>(parsed)
        assertEquals(Purpose.BIP49, parsed.purpose)
    }

    @Test
    fun parsesTrMainnet() {
        val parsed = Descriptor.parse(withChecksum("tr($mainnetAccountKey/0/*)"))
        assertIs<Descriptor.Tr>(parsed)
        assertEquals(Purpose.BIP86, parsed.purpose)
    }

    @Test
    fun inferTestnetFromTpub() {
        val parsed = Descriptor.parse(withChecksum("wpkh($testnetAccountKey/0/*)"))
        assertEquals(Network.TESTNET, parsed.network)
    }

    @Test
    fun rejectsMissingChecksum() {
        assertFailsWith<DescriptorException.InvalidChecksum> {
            Descriptor.parse("wpkh($mainnetAccountKey/0/*)")
        }
    }

    @Test
    fun rejectsWrongChecksum() {
        // 8-char string in CHECKSUM_CHARSET but not the right value
        assertFailsWith<DescriptorException.InvalidChecksum> {
            Descriptor.parse("wpkh($mainnetAccountKey/0/*)#qqqqqqqq")
        }
    }

    @Test
    fun rejectsWshMultisig() {
        val ex = assertFailsWith<DescriptorException.Unsupported> {
            // We never get to checksum verification because Descriptor.parse
            // always strips checksum first; supply a syntactically valid one.
            Descriptor.parse(
                withChecksum("wsh(multi(2,$mainnetAccountKey/0/*,$mainnetAccountKey/0/*))")
            )
        }
        assertTrue(ex.message!!.contains("wsh"))
    }

    @Test
    fun rejectsBareMulti() {
        assertFailsWith<DescriptorException.Unsupported> {
            Descriptor.parse(withChecksum("multi(2,$mainnetAccountKey,$mainnetAccountKey)"))
        }
    }

    @Test
    fun rejectsSortedmulti() {
        assertFailsWith<DescriptorException.Unsupported> {
            Descriptor.parse(withChecksum("sortedmulti(2,$mainnetAccountKey,$mainnetAccountKey)"))
        }
    }

    @Test
    fun rejectsTrWithScriptTree() {
        // tr(KEY, TREE) — the comma triggers the unsupported branch.
        val body = "tr($mainnetAccountKey,pk($mainnetAccountKey))"
        val ex = assertFailsWith<DescriptorException.Unsupported> {
            Descriptor.parse(withChecksum(body))
        }
        assertTrue(ex.message!!.contains("script tree"))
    }

    @Test
    fun rejectsShOfNonWpkh() {
        // sh(pkh(...)) is technically valid in Bitcoin Core but outside our subset.
        assertFailsWith<DescriptorException.Unsupported> {
            Descriptor.parse(withChecksum("sh(pkh($mainnetAccountKey/0/*))"))
        }
    }

    @Test
    fun rejectsPk() {
        assertFailsWith<DescriptorException.Unsupported> {
            Descriptor.parse(withChecksum("pk($mainnetAccountKey)"))
        }
    }

    @Test
    fun rejectsAddr() {
        assertFailsWith<DescriptorException.Unsupported> {
            Descriptor.parse(withChecksum("addr(bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu)"))
        }
    }

    @Test
    fun rejectsRawHexPubkey() {
        // A plain compressed pubkey (33 bytes, hex), not an extended key.
        val pubkeyHex = "03a34b99f22c790c4e36b2b3c2c35a36db06226e41c692fc82b8b56ac1c540c5bd"
        assertFailsWith<DescriptorException.Unsupported> {
            Descriptor.parse(withChecksum("wpkh($pubkeyHex)"))
        }
    }

    @Test
    fun rejectsUnknownDerivationSuffix() {
        // /1/* would mean "change chain only" — not useful for watch-only.
        assertFailsWith<DescriptorException.Unsupported> {
            Descriptor.parse(withChecksum("wpkh($mainnetAccountKey/1/*)"))
        }
    }

    @Test
    fun rejectsBrokenFingerprint() {
        // 7 hex chars instead of 8.
        assertFailsWith<DescriptorException.Malformed> {
            Descriptor.parse(withChecksum("wpkh([deadbee/84h/0h/0h]$mainnetAccountKey/0/*)"))
        }
    }

    @Test
    fun rejectsNonHexFingerprint() {
        assertFailsWith<DescriptorException.Malformed> {
            Descriptor.parse(withChecksum("wpkh([zzzzzzzz/84h/0h/0h]$mainnetAccountKey/0/*)"))
        }
    }

    @Test
    fun rejectsBadPathStep() {
        assertFailsWith<DescriptorException.Malformed> {
            Descriptor.parse(withChecksum("wpkh([deadbeef/84h/foo/0h]$mainnetAccountKey/0/*)"))
        }
    }

    @Test
    fun rejectsMalformedTopLevel() {
        assertFailsWith<DescriptorException.Malformed> {
            Descriptor.parse(withChecksum("notawrapper"))
        }
    }
}
