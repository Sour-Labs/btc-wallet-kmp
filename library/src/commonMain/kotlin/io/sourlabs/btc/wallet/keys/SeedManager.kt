package io.sourlabs.btc.wallet.keys

import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.MnemonicCode.englishWordlist
import fr.acinq.bitcoin.crypto.Pbkdf2

/**
 * Manages the creation of extended keys and key derivation.
 *
 * Here are the steps:
 * 1) First generate a random entropy (a simple array of bytes, between 128 and 256 bits depending on the number of words you need)
 * 2) Then use the entropy to generate a random mnemonic code (also called seed phrase, which is a simple list of words randomly selected from a dictionary)
 * 3) Then use the mnemonic code to generate a seed (a simple array of 64 bytes, or 512 bits)
 * 4) Then use the seed to generate a master key
 */
object SeedManager {

    /**
     * Represents the size of a mnemonic code in terms of the number of words.
     *
     * @property wordCount The number of words in the mnemonic.
     */
    enum class MnemonicSize(val wordCount: Int) {
        /** 12 words mnemonic. */
        Min(12),

        /** 15 words mnemonic. */
        Low(15),

        /** 18 words mnemonic. */
        Medium(18),

        /** 21 words mnemonic. */
        High(21),

        /** 24 words mnemonic. */
        Max(24);

        /** The length of the checksum in bits. */
        val checksumLength: Int
            get() = wordCount / 3

        /** The length of the entropy in bits. */
        val entropyLength: Int
            get() = checksumLength * 32

        /** The total length of the mnemonic in bits. */
        val totalLength: Int
            get() = entropyLength + checksumLength
    }

    /**
     * Generates a random byte array of the specified length for entropy.
     *
     * @param entropyLength The desired length of the entropy in bits.
     * @return A [ByteArray] containing the generated entropy.
     */
    internal fun generateEntropy(entropyLength: Int): ByteArray {
        val entropyBytes = entropyLength / 8
        return secureRandomBytes(entropyBytes)
    }

    /**
     * Generates a new mnemonic code.
     *
     * TODO: Support more languages
     *
     * @param mnemonicSize The desired size of the mnemonic. Defaults to [MnemonicSize.Min] with 12 words.
     * @return A list of strings representing the mnemonic code.
     */
    fun generateMnemonicCode(mnemonicSize: MnemonicSize = MnemonicSize.Max): List<String> {
        val entropy = generateEntropy(mnemonicSize.entropyLength)
        return MnemonicCode.toMnemonics(entropy)
    }

    /**
     * Converts a mnemonic code (list of strings) into a seed.
     *
     * This function takes the mnemonic code (as a list of strings) and an optional passphrase to generate a seed using PBKDF2 with HMAC-SHA512.
     * It follows the common practice of using "mnemonic" concatenated with the passphrase as the salt.
     * Before conversion, this function also validates the mnemonic code.
     *
     * @receiver The mnemonic code, represented as a list of strings.
     * @param passphrase An optional passphrase to enhance the security of the seed. Defaults to an empty string.
     * @return A 64-byte (512-bit) seed as a [ByteArray].
     * @throws IllegalArgumentException if the mnemonic is not valid.
     */
    fun List<String>.toSeed(passphrase: String = ""): ByteArray {
        return MnemonicCode.toSeed(this, passphrase)
    }

    /**
     * Validates a mnemonic code against a wordlist.
     *
     * This function performs several checks to ensure the validity of a mnemonic phrase:
     * - The wordlist must contain 2048 words.
     * - The mnemonic code cannot be empty.
     * - The word count must be a multiple of 3.
     * - All words in the mnemonic must be present in the wordlist.
     * - The checksum of the mnemonic must be valid.
     *
     * @receiver The mnemonic code to validate, represented as a list of strings.
     * @param wordlist The wordlist to use for validation. Defaults to the English wordlist from [MnemonicCode.englishWordlist].
     * @throws IllegalArgumentException if the mnemonic code is invalid.
     */
    fun List<String>.validate(wordlist: List<String> = englishWordlist) {
        MnemonicCode.validate(mnemonics = this, wordlist = wordlist)
    }
}