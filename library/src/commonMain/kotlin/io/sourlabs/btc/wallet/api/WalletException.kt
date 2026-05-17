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
 * Exception thrown when a transaction amount or fee rate fails validation
 * before the wallet builds the transaction. Common cases:
 *  - Non-positive amount.
 *  - Amount below the destination's dust threshold (and not subtracting fee).
 *  - Fee rate below the standard 1 sat/vB minimum relay fee.
 *
 * Distinguished from [InsufficientFundsException], which is raised after
 * UTXO selection determines the wallet can't cover the cost.
 */
class InvalidAmountException(message: String) : WalletException(message)

/**
 * Exception thrown when sync fails.
 */
class SyncException(
    message: String,
    cause: Throwable? = null
) : WalletException(message, cause)

/**
 * Exception thrown when a wallet restoration scan aborts after too many
 * consecutive API failures. The scan result up to that point is discarded —
 * a partial scan can't be trusted to tell "no funds" from "incomplete scan."
 */
class ScanException(
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

/**
 * Exception thrown when an output descriptor cannot be parsed or is unsupported.
 *
 * Supported wrappers: pkh, sh(wpkh), wpkh, tr (key-path only).
 * Anything else (multisig, miniscript, tr script trees, MuSig2) raises this.
 */
sealed class DescriptorException(message: String) : WalletException(message) {

    /** The descriptor body is syntactically invalid (malformed grammar, bad characters, etc.). */
    class Malformed(message: String) : DescriptorException(message)

    /** The descriptor's `#xxxxxxxx` checksum is missing or doesn't match the body. */
    class InvalidChecksum(message: String = "Descriptor checksum is missing or invalid") :
        DescriptorException(message)

    /** The descriptor is well-formed but uses a wrapper or feature that is not supported. */
    class Unsupported(message: String) : DescriptorException(message)
}
