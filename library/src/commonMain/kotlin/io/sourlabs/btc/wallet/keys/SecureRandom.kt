package io.sourlabs.btc.wallet.keys

/**
 * Platform-specific cryptographically secure random bytes.
 */
internal expect fun secureRandomBytes(size: Int): ByteArray
