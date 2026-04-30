package io.sourlabs.btc.wallet.descriptors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DescriptorChecksumTest {

    /**
     * Round-trip: a body fed to compute() then back through verifyAndStrip()
     * with the appended checksum should round-trip cleanly.
     */
    @Test
    fun computeRoundTrip() {
        val body = "wpkh([deadbeef/84h/0h/0h]xpub6CR3jBPmDmxw23v5cgTBjCxpkfVNTLcdyVAj1nUWttdkjHcwzMSGY8RmkFLXRfLuxwukHCKHNQRC9XEnFDxzdKuxBrmXMyUWeBkNLrVoMt6/0/*)"
        val checksum = DescriptorChecksum.compute(body)
        assertNotNull(checksum)
        assertEquals(8, checksum.length)

        val full = "$body#$checksum"
        val stripped = DescriptorChecksum.verifyAndStrip(full)
        assertEquals(body, stripped)
    }

    /**
     * A body containing a character outside the BIP-380 INPUT_CHARSET (e.g.
     * a non-ASCII letter) cannot be expanded — compute() returns null.
     */
    @Test
    fun rejectsBodyWithNonInputChar() {
        // 'ñ' is not in INPUT_CHARSET
        val body = "wpkh(xpubñ)"
        assertNull(DescriptorChecksum.compute(body))
    }

    @Test
    fun verifyMissingChecksum() {
        assertNull(DescriptorChecksum.verifyAndStrip("wpkh(xpub6CR...)"))
    }

    @Test
    fun verifyMalformedChecksum() {
        val body = "wpkh(xpub6CR)"
        // Wrong length
        assertNull(DescriptorChecksum.verifyAndStrip("$body#abc"))
        // Wrong characters (capital letters not in CHECKSUM_CHARSET)
        assertNull(DescriptorChecksum.verifyAndStrip("$body#ABCDEFGH"))
    }

    @Test
    fun verifyChecksumMismatch() {
        val body = "wpkh(xpub6CR)"
        val correct = DescriptorChecksum.compute(body)!!
        val flipped = (correct.first().let { if (it == 'q') 'p' else 'q' }) + correct.drop(1)
        assertTrue(correct != flipped)
        assertNull(DescriptorChecksum.verifyAndStrip("$body#$flipped"))
    }
}
