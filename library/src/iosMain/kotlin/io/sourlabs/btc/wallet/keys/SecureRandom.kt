@file:OptIn(ExperimentalForeignApi::class)

package io.sourlabs.btc.wallet.keys

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

internal actual fun secureRandomBytes(size: Int): ByteArray {
    require(size >= 0) { "Size must be non-negative" }
    if (size == 0) {
        return ByteArray(0)
    }
    val bytes = ByteArray(size)
    val status = bytes.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, size.toULong(), pinned.addressOf(0))
    }
    if (status != errSecSuccess) {
        throw IllegalStateException("SecRandomCopyBytes failed with status $status")
    }
    return bytes
}
