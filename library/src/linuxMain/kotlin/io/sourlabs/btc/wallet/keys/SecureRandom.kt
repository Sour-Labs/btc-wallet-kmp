@file:OptIn(ExperimentalForeignApi::class)

package io.sourlabs.btc.wallet.keys

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.open
import platform.posix.read

internal actual fun secureRandomBytes(size: Int): ByteArray {
    require(size >= 0) { "Size must be non-negative" }
    if (size == 0) {
        return ByteArray(0)
    }
    val bytes = ByteArray(size)
    val fd = open("/dev/urandom", O_RDONLY)
    if (fd < 0) {
        throw IllegalStateException("Unable to open /dev/urandom")
    }
    try {
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < size) {
                val readBytes = read(fd, pinned.addressOf(offset), (size - offset).toULong())
                if (readBytes > 0) {
                    offset += readBytes.toInt()
                } else if (readBytes < 0 && errno == EINTR) {
                    continue
                } else {
                    throw IllegalStateException("Unable to read from /dev/urandom")
                }
            }
        }
    } finally {
        close(fd)
    }
    return bytes
}
