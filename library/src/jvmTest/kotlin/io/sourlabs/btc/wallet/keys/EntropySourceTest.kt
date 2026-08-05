package io.sourlabs.btc.wallet.keys

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Pins the entropy source of the key-generation path at the source level.
 *
 * No black-box test separates a CSPRNG from a competent non-cryptographic
 * PRNG: `kotlin.random.Random` passes every statistical check that fits in a
 * unit test, and its output is the right length and never repeats. That blind
 * spot is not theoretical. This library generated mnemonic entropy with
 * `kotlin.random.Random.nextBytes` until `5f3f97c` (pre-release; the earliest
 * published version is 0.4.0 and no released version is affected), and the
 * Coldcard firmware disclosed in July 2026 silently resolved seed generation
 * to a software PRNG for five years. Neither suite noticed, because in both
 * cases the bytes look correct.
 *
 * So these tests assert what the shipped key-generation code is allowed to
 * call, not what it returns. [SeedManagerTest] covers the behaviour.
 *
 * JVM-only because it reads the source tree, which Kotlin/Native cannot. CI
 * runs `jvmTest` on every pull request and on `main`.
 */
class EntropySourceTest {

    @Test
    fun keyGenerationUsesNoNonCryptographicRandomness() {
        val sources = keySources()
        // A broken path must fail the test rather than pass an empty scan.
        if (sources.isEmpty()) fail("Scanned no sources under $KEYS_PACKAGE in $sourceRoot")

        val offenders = sources.filter { NON_CRYPTO.containsMatchIn(it.readText()) }
        if (offenders.isNotEmpty()) {
            fail(
                "Non-cryptographic randomness in the key-generation path: " +
                    offenders.joinToString { it.relativeTo(sourceRoot).path } +
                    ". Key material must come from secureRandomBytes()."
            )
        }
    }

    @Test
    fun everyPlatformActualBindsItsSystemCsprng() {
        for ((sourceSet, binding) in SYSTEM_CSPRNG) {
            val actual = sourceRoot.resolve("$sourceSet/kotlin/$KEYS_PACKAGE/SecureRandom.kt")
            if (!actual.isFile) fail("No secureRandomBytes actual for $sourceSet at $actual")

            val text = actual.readText()
            if (!text.contains(binding.import)) {
                fail("$sourceSet secureRandomBytes no longer imports ${binding.import}")
            }
            // Checked against the body, not the whole file: an import Kotlin only
            // warns about, or the primitive's own name inside an error message,
            // must not stand in for calling it.
            val body = text.lineSequence().filterNot { it.trimStart().startsWith("import ") }
            if (body.none { it.contains(binding.call) }) {
                fail("$sourceSet secureRandomBytes no longer calls ${binding.call}")
            }
        }
    }

    /**
     * Every Kotlin file in the `keys` package of every main source set. Test
     * source sets are excluded: the guard is about what ships, and a test may
     * legitimately want a seeded generator.
     */
    private fun keySources(): List<File> =
        (sourceRoot.listFiles() ?: emptyArray())
            .filter { it.name.endsWith("Main") }
            .map { it.resolve("kotlin/$KEYS_PACKAGE") }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }

    /** The system CSPRNG a target imports, and the call that proves it uses it. */
    private data class Binding(val import: String, val call: String)

    private companion object {
        const val KEYS_PACKAGE = "io/sourlabs/btc/wallet/keys"

        val SYSTEM_CSPRNG = mapOf(
            "androidMain" to Binding("java.security.SecureRandom", "SecureRandom()"),
            "jvmMain" to Binding("java.security.SecureRandom", "SecureRandom()"),
            "iosMain" to Binding("platform.Security.SecRandomCopyBytes", "SecRandomCopyBytes("),
            // The device path is the mechanism here, so the quoted literal is
            // the call. Quoted so the error message mentioning the same path
            // cannot satisfy it.
            "linuxMain" to Binding("platform.posix.open", "\"/dev/urandom\""),
        )

        /**
         * Both are seeded, reproducible generators that belong nowhere near key
         * material. Matching the package rather than the type name also catches
         * fully-qualified use, since `Random` cannot be named without it.
         */
        val NON_CRYPTO = Regex("""kotlin\.random|java\.util\.Random""")

        /** `library/src`, found by walking up from the test's working directory. */
        val sourceRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(it.resolve("src"), it.resolve("library/src")) }
            .firstOrNull { it.resolve("commonMain/kotlin/$KEYS_PACKAGE").isDirectory }
            ?: error("Could not locate library/src from ${File("").absolutePath}")
    }
}
