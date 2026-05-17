package io.sourlabs.btc.wallet.core

import io.sourlabs.btc.wallet.api.DescriptorException
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Anchors PR-11 (audit finding M5): a malformed descriptor must surface
 * the parse failure from a clearly construction-related code path (the
 * `init {}` block) rather than from a data-class property initializer
 * that's harder to read in a stack trace.
 *
 * The behavior is observable as "the exception type is what the contract
 * documents" — DescriptorException for an unparseable descriptor — but the
 * point of this test is to lock that exception type in place so a future
 * refactor doesn't re-introduce the property-initializer style.
 */
class WatchOnlyDescriptorTest {

    @Test
    fun missingChecksumThrowsDescriptorException() {
        // No `#xxxxxxxx` checksum — DescriptorException.InvalidChecksum.
        assertFailsWith<DescriptorException> {
            WalletConfig.WatchOnlyDescriptor(descriptor = "wpkh(xpub6...)")
        }
    }

    @Test
    fun completelyMalformedThrowsDescriptorException() {
        assertFailsWith<DescriptorException> {
            WalletConfig.WatchOnlyDescriptor(descriptor = "this-is-not-a-descriptor")
        }
    }

    @Test
    fun gapLimitValidationStillRunsForValidDescriptor() {
        // require(gapLimit > 0) is also in init{}; verify the order doesn't
        // break it (the data class shouldn't silently accept gapLimit=0 just
        // because the parse step was deferred).
        val validDescriptor = buildValidWpkhDescriptor()
        assertFailsWith<IllegalArgumentException> {
            WalletConfig.WatchOnlyDescriptor(
                descriptor = validDescriptor,
                gapLimit = 0,
            )
        }
    }

    private fun buildValidWpkhDescriptor(): String {
        // Account-level zpub from the BIP-84 canonical test vector wallet.
        val zpub = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
        val body = "wpkh($zpub/0/*)"
        val checksum = io.sourlabs.btc.wallet.descriptors.DescriptorChecksum.compute(body)
        return "$body#$checksum"
    }
}
