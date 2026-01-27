package io.sourlabs.btc.wallet.keys

import java.security.SecureRandom

private val secureRandom = SecureRandom()

internal actual fun secureRandomBytes(size: Int): ByteArray {
    require(size >= 0) { "Size must be non-negative" }
    if (size == 0) {
        return ByteArray(0)
    }
    val bytes = ByteArray(size)
    secureRandom.nextBytes(bytes)
    return bytes
}
