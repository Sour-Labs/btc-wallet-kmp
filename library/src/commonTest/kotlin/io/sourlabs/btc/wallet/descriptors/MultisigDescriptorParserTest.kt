package io.sourlabs.btc.wallet.descriptors

import io.sourlabs.btc.wallet.api.DescriptorException
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MultisigDescriptorParserTest {

    // BIP-84 published account-level zpub for the "abandon...about" mnemonic.
    private val mainnetXpub =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    // Three independent mainnet xpubs from the user's Bitkey 2-of-3 wallet export.
    // These are watch-only public keys with no funds; safe to embed as a test vector.
    private val bitkeyKey1Origin = "151c0436/84'/0'/0'"
    private val bitkeyKey1 =
        "xpub6CKrVEyoK68fJ5WnniiFoiQCsXa32rnM7rHXoiFZji1g9nJdgWtydoXfWQeGSt4LVjwptLQTnZfdKV2b37Ux5asMBfDXt2KBzFJKU2i5vfr"
    private val bitkeyKey2Origin = "d0ec9d96/84'/0'/0'"
    private val bitkeyKey2 =
        "xpub6D6DBgdzpTbAL4Ln4ufFiYdiRKRKg1cMgDtzYGHSmyuHxvUBqkfjQsc7rDc1y66i1v5KUKLSNU6AbG69kEQDNbyGY5KZ6tZL9dFg8to9Ekc"
    private val bitkeyKey3Origin = "c6f75db6/84'/0'/0'"
    private val bitkeyKey3 =
        "xpub6Cv5iQBhcq7buXp5VbrAzzH3tPNTDCvzLFkFVhrc4Ybet8EHH6oSs16UuD2FMmAHQn7TiuCVtBuPNz7rCjsg9pdEKBo7aCUAiMGFbt9W387"

    private val bitkeyExternalBody =
        "wsh(sortedmulti(2," +
            "[$bitkeyKey1Origin]$bitkeyKey1/0/*," +
            "[$bitkeyKey2Origin]$bitkeyKey2/0/*," +
            "[$bitkeyKey3Origin]$bitkeyKey3/0/*" +
            "))"

    private fun withChecksum(body: String): String =
        "$body#${DescriptorChecksum.compute(body)}"

    @Test
    fun parsesBitkeyExternalDescriptor() {
        val parsed = MultisigDescriptor.parse(withChecksum(bitkeyExternalBody))
        val multi = assertIs<MultisigDescriptor.WshSortedMulti>(parsed)
        assertEquals(2, multi.threshold)
        assertEquals(3, multi.keys.size)
        assertEquals(Network.MAINNET, multi.network)
        assertEquals(Purpose.BIP84, multi.purpose)
        assertTrue(multi.sorted)

        val fingerprints = multi.keys.map { it.keyOrigin?.fingerprint?.toHex() }
        assertEquals(listOf("151c0436", "d0ec9d96", "c6f75db6"), fingerprints)

        assertEquals(bitkeyKey1, multi.keys[0].extendedPublicKey)
        assertEquals(bitkeyKey2, multi.keys[1].extendedPublicKey)
        assertEquals(bitkeyKey3, multi.keys[2].extendedPublicKey)
    }

    @Test
    fun parsesMultipathSuffix() {
        // Canonical normalized form a wallet might emit, combining external+internal.
        val body = "wsh(sortedmulti(2,$mainnetXpub/<0;1>/*,$mainnetXpub/<0;1>/*))"
        val parsed = MultisigDescriptor.parse(withChecksum(body))
        assertIs<MultisigDescriptor.WshSortedMulti>(parsed)
    }

    @Test
    fun parsesBraceListSuffix() {
        val body = "wsh(sortedmulti(2,$mainnetXpub/{0,1}/*,$mainnetXpub/{0,1}/*))"
        val parsed = MultisigDescriptor.parse(withChecksum(body))
        assertIs<MultisigDescriptor.WshSortedMulti>(parsed)
    }

    @Test
    fun parsesTwoOfTwoNoKeyOrigins() {
        val body = "wsh(sortedmulti(2,$mainnetXpub/0/*,$mainnetXpub/0/*))"
        val parsed = MultisigDescriptor.parse(withChecksum(body))
        val multi = assertIs<MultisigDescriptor.WshSortedMulti>(parsed)
        assertEquals(2, multi.threshold)
        assertEquals(2, multi.keys.size)
        // Two keys without origin info — both must parse successfully even though
        // they reuse the same xpub (this is a degenerate test config, not a real
        // wallet, but the parser shouldn't dedupe).
        assertEquals(null, multi.keys[0].keyOrigin)
        assertEquals(null, multi.keys[1].keyOrigin)
    }

    @Test
    fun parsesSingleKeyOneOfOne() {
        // Degenerate 1-of-1 — equivalent to a single-sig wsh(wpkh)-style address
        // but expressed via sortedmulti. Should still parse.
        val body = "wsh(sortedmulti(1,$mainnetXpub/0/*))"
        val parsed = MultisigDescriptor.parse(withChecksum(body))
        val multi = assertIs<MultisigDescriptor.WshSortedMulti>(parsed)
        assertEquals(1, multi.threshold)
        assertEquals(1, multi.keys.size)
    }

    @Test
    fun rejectsThresholdAboveKeyCount() {
        val body = "wsh(sortedmulti(3,$mainnetXpub/0/*,$mainnetXpub/0/*))"
        val ex = assertFailsWith<DescriptorException.Malformed> {
            MultisigDescriptor.parse(withChecksum(body))
        }
        assertTrue(ex.message!!.contains("exceeds key count"))
    }

    @Test
    fun rejectsZeroThreshold() {
        val body = "wsh(sortedmulti(0,$mainnetXpub/0/*))"
        val ex = assertFailsWith<DescriptorException.Malformed> {
            MultisigDescriptor.parse(withChecksum(body))
        }
        assertTrue(ex.message!!.contains("threshold"))
    }

    @Test
    fun rejectsMixedNetworks() {
        val testnetXpub =
            "tpubDCsfvDP3W6XZUNvW6FDoCLqWgvD2BkpsK6foLBcETtm6Pde9SwoxRA1eaLA1WW6mDDuxYHb2NQfbHWxxupTtUdYBjJVBpejP1NMSJTrYsdW"
        val body = "wsh(sortedmulti(2,$mainnetXpub/0/*,$testnetXpub/0/*))"
        val ex = assertFailsWith<DescriptorException.Malformed> {
            MultisigDescriptor.parse(withChecksum(body))
        }
        assertTrue(ex.message!!.contains("same network"))
    }

    @Test
    fun rejectsMixedDerivationSuffixes() {
        // One key has /0/*, the other /<0;1>/* — inconsistent, must be rejected.
        val body = "wsh(sortedmulti(2,$mainnetXpub/0/*,$mainnetXpub/<0;1>/*))"
        val ex = assertFailsWith<DescriptorException.Malformed> {
            MultisigDescriptor.parse(withChecksum(body))
        }
        assertTrue(ex.message!!.contains("same derivation suffix"))
    }

    @Test
    fun rejectsWshOfPlainMulti() {
        // wsh(multi(...)) — unsorted multisig. Reject explicitly to point users
        // at sortedmulti, which is what every modern wallet emits.
        val body = "wsh(multi(2,$mainnetXpub/0/*,$mainnetXpub/0/*))"
        val ex = assertFailsWith<DescriptorException.Unsupported> {
            MultisigDescriptor.parse(withChecksum(body))
        }
        assertTrue(ex.message!!.contains("sortedmulti"))
    }

    @Test
    fun rejectsBareSortedmulti() {
        // Top-level sortedmulti without wsh() — bare P2MS, non-standard.
        val body = "sortedmulti(2,$mainnetXpub/0/*,$mainnetXpub/0/*)"
        val ex = assertFailsWith<DescriptorException.Unsupported> {
            MultisigDescriptor.parse(withChecksum(body))
        }
        assertTrue(ex.message!!.contains("bare P2MS") || ex.message!!.contains("wsh"))
    }

    @Test
    fun rejectsNonWshWrapper() {
        // wpkh — single-key, not multisig. Caller should use Descriptor.parse instead.
        val body = "wpkh($mainnetXpub/0/*)"
        val ex = assertFailsWith<DescriptorException.Unsupported> {
            MultisigDescriptor.parse(withChecksum(body))
        }
        assertTrue(ex.message!!.contains("wsh"))
    }

    @Test
    fun rejectsMissingChecksum() {
        assertFailsWith<DescriptorException.InvalidChecksum> {
            MultisigDescriptor.parse(bitkeyExternalBody)
        }
    }

    @Test
    fun rejectsWrongChecksum() {
        assertFailsWith<DescriptorException.InvalidChecksum> {
            MultisigDescriptor.parse("$bitkeyExternalBody#qqqqqqqq")
        }
    }

    @Test
    fun rejectsTooManyKeys() {
        // Build a 17-key descriptor — exceeds the OP_CHECKMULTISIG 16-key ceiling.
        val keyList = List(17) { "$mainnetXpub/0/*" }.joinToString(",")
        val body = "wsh(sortedmulti(2,$keyList))"
        val ex = assertFailsWith<DescriptorException.Unsupported> {
            MultisigDescriptor.parse(withChecksum(body))
        }
        assertTrue(ex.message!!.contains("16"))
    }

    @Test
    fun keyOriginPathsParsedCorrectly() {
        val parsed = MultisigDescriptor.parse(withChecksum(bitkeyExternalBody))
        val multi = assertIs<MultisigDescriptor.WshSortedMulti>(parsed)
        val origin = assertNotNull(multi.keys[0].keyOrigin)
        assertEquals(3, origin.path.size)
        assertEquals(Descriptor.PathStep(84, true), origin.path[0])
        assertEquals(Descriptor.PathStep(0, true), origin.path[1])
        assertEquals(Descriptor.PathStep(0, true), origin.path[2])
    }

    private fun ByteArray.toHex(): String = joinToString("") {
        val hex = (it.toInt() and 0xff).toString(16)
        if (hex.length == 1) "0$hex" else hex
    }
}
