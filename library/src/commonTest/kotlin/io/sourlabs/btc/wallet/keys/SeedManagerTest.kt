package io.sourlabs.btc.wallet.keys

import io.sourlabs.btc.wallet.keys.SeedManager.toSeed
import io.sourlabs.btc.wallet.keys.SeedManager.validate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SeedManagerTest {

    // Standard BIP39 test mnemonic (all "abandon" + "about")
    private val validMnemonic12 = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    // ---- MnemonicSize enum tests ----

    @Test
    fun testMnemonicSizeWordCounts() {
        assertEquals(12, SeedManager.MnemonicSize.Min.wordCount)
        assertEquals(15, SeedManager.MnemonicSize.Low.wordCount)
        assertEquals(18, SeedManager.MnemonicSize.Medium.wordCount)
        assertEquals(21, SeedManager.MnemonicSize.High.wordCount)
        assertEquals(24, SeedManager.MnemonicSize.Max.wordCount)
    }

    @Test
    fun testMnemonicSizeChecksumLength() {
        assertEquals(4, SeedManager.MnemonicSize.Min.checksumLength)
        assertEquals(5, SeedManager.MnemonicSize.Low.checksumLength)
        assertEquals(6, SeedManager.MnemonicSize.Medium.checksumLength)
        assertEquals(7, SeedManager.MnemonicSize.High.checksumLength)
        assertEquals(8, SeedManager.MnemonicSize.Max.checksumLength)
    }

    @Test
    fun testMnemonicSizeEntropyLength() {
        assertEquals(128, SeedManager.MnemonicSize.Min.entropyLength)
        assertEquals(160, SeedManager.MnemonicSize.Low.entropyLength)
        assertEquals(192, SeedManager.MnemonicSize.Medium.entropyLength)
        assertEquals(224, SeedManager.MnemonicSize.High.entropyLength)
        assertEquals(256, SeedManager.MnemonicSize.Max.entropyLength)
    }

    @Test
    fun testMnemonicSizeTotalLength() {
        assertEquals(132, SeedManager.MnemonicSize.Min.totalLength)
        assertEquals(165, SeedManager.MnemonicSize.Low.totalLength)
        assertEquals(198, SeedManager.MnemonicSize.Medium.totalLength)
        assertEquals(231, SeedManager.MnemonicSize.High.totalLength)
        assertEquals(264, SeedManager.MnemonicSize.Max.totalLength)
    }

    // ---- generateEntropy tests ----

    @Test
    fun testGenerateEntropyReturnsCorrectSize() {
        assertEquals(16, SeedManager.generateEntropy(128).size)
        assertEquals(20, SeedManager.generateEntropy(160).size)
        assertEquals(24, SeedManager.generateEntropy(192).size)
        assertEquals(28, SeedManager.generateEntropy(224).size)
        assertEquals(32, SeedManager.generateEntropy(256).size)
    }

    @Test
    fun testGenerateEntropyIsRandom() {
        val entropy1 = SeedManager.generateEntropy(128)
        val entropy2 = SeedManager.generateEntropy(128)
        // Two random 128-bit values should not be equal
        assertNotEquals(entropy1.toList(), entropy2.toList())
    }

    // ---- generateMnemonicCode tests ----

    @Test
    fun testGenerateMnemonicCodeDefaultSize() {
        val mnemonic = SeedManager.generateMnemonicCode()
        assertEquals(24, mnemonic.size)
    }

    @Test
    fun testGenerateMnemonicCodeAllSizes() {
        for (size in SeedManager.MnemonicSize.entries) {
            val mnemonic = SeedManager.generateMnemonicCode(size)
            assertEquals(size.wordCount, mnemonic.size)
        }
    }

    @Test
    fun testGenerateMnemonicCodeProducesValidMnemonic() {
        for (size in SeedManager.MnemonicSize.entries) {
            val mnemonic = SeedManager.generateMnemonicCode(size)
            // Should not throw
            mnemonic.validate()
        }
    }

    @Test
    fun testGenerateMnemonicCodeIsRandom() {
        val mnemonic1 = SeedManager.generateMnemonicCode()
        val mnemonic2 = SeedManager.generateMnemonicCode()
        assertNotEquals(mnemonic1, mnemonic2)
    }

    // ---- validate tests ----

    @Test
    fun testValidateAcceptsValidMnemonic() {
        // Should not throw
        validMnemonic12.validate()
    }

    @Test
    fun testValidateRejectsEmptyMnemonic() {
        assertFailsWith<Exception> {
            emptyList<String>().validate()
        }
    }

    @Test
    fun testValidateRejectsInvalidWordCount() {
        // 11 words is not a valid mnemonic length
        val invalidMnemonic = validMnemonic12.dropLast(1)
        assertFailsWith<Exception> {
            invalidMnemonic.validate()
        }
    }

    @Test
    fun testValidateRejectsInvalidWords() {
        val invalidMnemonic = validMnemonic12.toMutableList().apply {
            this[0] = "notavalidword"
        }
        assertFailsWith<Exception> {
            invalidMnemonic.validate()
        }
    }

    @Test
    fun testValidateRejectsInvalidChecksum() {
        // Replace the last word to break the checksum
        val invalidMnemonic = validMnemonic12.toMutableList().apply {
            this[11] = "abandon" // "abandon" x12 has an invalid checksum
        }
        assertFailsWith<Exception> {
            invalidMnemonic.validate()
        }
    }

    // ---- toSeed tests ----

    @Test
    fun testToSeedReturns64Bytes() {
        val seed = validMnemonic12.toSeed()
        assertEquals(64, seed.size)
    }

    @Test
    fun testToSeedIsDeterministic() {
        val seed1 = validMnemonic12.toSeed()
        val seed2 = validMnemonic12.toSeed()
        assertEquals(seed1.toList(), seed2.toList())
    }

    @Test
    fun testToSeedWithPassphrase() {
        val seedNoPassphrase = validMnemonic12.toSeed()
        val seedWithPassphrase = validMnemonic12.toSeed("mypassphrase")
        // Different passphrase should produce a different seed
        assertNotEquals(seedNoPassphrase.toList(), seedWithPassphrase.toList())
    }

    @Test
    fun testToSeedDifferentPassphrasesProduceDifferentSeeds() {
        val seed1 = validMnemonic12.toSeed("password1")
        val seed2 = validMnemonic12.toSeed("password2")
        assertNotEquals(seed1.toList(), seed2.toList())
    }

    @Test
    fun testToSeedMatchesBip39TestVector() {
        // BIP39 test vector: mnemonic "abandon" x11 + "about", no passphrase
        // Expected seed from the BIP39 specification
        val seed = validMnemonic12.toSeed()
        val seedHex = seed.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            seedHex
        )
    }

    @Test
    fun testToSeedWithPassphraseMatchesBip39TestVector() {
        // BIP39 test vector: mnemonic "abandon" x11 + "about", passphrase "TREZOR"
        val seed = validMnemonic12.toSeed("TREZOR")
        val seedHex = seed.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        assertEquals(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e534955" +
                "31f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            seedHex
        )
    }

    @Test
    fun testToSeedRejectsInvalidMnemonic() {
        val invalidMnemonic = listOf("not", "a", "valid", "mnemonic")
        assertFailsWith<Exception> {
            invalidMnemonic.toSeed()
        }
    }

    // ---- Integration: generate and convert ----

    @Test
    fun testGenerateAndConvertToSeed() {
        val mnemonic = SeedManager.generateMnemonicCode(SeedManager.MnemonicSize.Max)
        val seed = mnemonic.toSeed()
        assertEquals(64, seed.size)
        // Seed should be deterministic for the same mnemonic
        val seed2 = mnemonic.toSeed()
        assertEquals(seed.toList(), seed2.toList())
    }
}
