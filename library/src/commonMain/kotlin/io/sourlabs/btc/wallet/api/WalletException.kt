package io.sourlabs.btc.wallet.api

/**
 * Base exception for wallet errors.
 */
open class WalletException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Exception thrown when wallet initialization fails.
 */
class WalletInitializationException(
    message: String,
    cause: Throwable? = null
) : WalletException(message, cause)

/**
 * Exception thrown for invalid addresses.
 */
class InvalidAddressException(
    val address: String,
    message: String = "Invalid address: $address"
) : WalletException(message)

/**
 * Exception thrown when sync fails.
 */
class SyncException(
    message: String,
    cause: Throwable? = null
) : WalletException(message, cause)

/**
 * Exception thrown when broadcast fails.
 */
class BroadcastException(
    message: String,
    cause: Throwable? = null
) : WalletException(message, cause)

/**
 * Exception thrown for signing errors.
 */
class SigningException(
    message: String,
    cause: Throwable? = null
) : WalletException(message, cause)
