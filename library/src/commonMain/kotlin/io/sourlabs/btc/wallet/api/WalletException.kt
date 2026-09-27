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
 * Exception thrown when the wallet cannot build a PSBT for an external signer,
 * or refuses what the signer returned.
 * Insufficient funds still raise [io.sourlabs.btc.wallet.transactions.InsufficientFundsException].
 */
sealed class PsbtException(
    message: String,
    cause: Throwable? = null
) : WalletException(message, cause) {

    /**
     * The wallet has no `[fingerprint/path]` key origin: it was built from a bare
     * extended public key, or from a descriptor without one. A signer finds its
     * keys in a PSBT through that origin, so it could not sign.
     */
    class MissingKeyOrigin(
        message: String = "Wallet has no key origin; build it from a descriptor with [fingerprint/path]"
    ) : PsbtException(message)

    /**
     * The key origin doesn't describe the wallet's account key: its path must
     * have one step per BIP-32 level and end at that key's child number.
     * Derivations built from it would match no key the signer holds.
     */
    class KeyOriginMismatch(
        val keyOrigin: String
    ) : PsbtException("Key origin $keyOrigin does not describe the wallet's account key")

    /** The wallet is multisig, which cannot build a PSBT yet. */
    class MultisigNotSupported(
        message: String = "Multisig wallets cannot build a PSBT"
    ) : PsbtException(message)

    /** The transaction that created one of the spent outputs could not be fetched. */
    class ParentTransactionUnavailable(
        val txId: String,
        cause: Throwable? = null
    ) : PsbtException("Could not fetch parent transaction $txId", cause)

    /**
     * A parent transaction contradicts the wallet's records: the explorer
     * served a transaction that isn't [txId], or the parent's output differs
     * from the stored UTXO (a stale or wrong UTXO record, whether the parent
     * came from storage or from the explorer). A refresh can repair the local
     * records; an explorer that serves the wrong transaction needs replacing.
     */
    class ParentTransactionMismatch(
        val txId: String
    ) : PsbtException("Parent transaction $txId does not match the wallet's records")

    /**
     * The draft or signed transaction was made by a kit for another wallet;
     * finalizing or recording it here would touch this wallet's keys.
     */
    class OtherWallet : PsbtException("Made by a kit for another wallet")

    /** What the signer returned is neither a PSBT nor a transaction. */
    class UnrecognizedSignedData(
        cause: Throwable? = null
    ) : PsbtException("Signed data is neither a PSBT nor a transaction", cause)

    /**
     * What the signer returned is not the transaction in the unsigned PSBT:
     * its [part] differs.
     */
    class SignedTransactionMismatch(
        val part: Part
    ) : PsbtException("Signed transaction differs from the one the wallet built: ${part.name.lowercase()}") {
        /** The part of the transaction that differs, checked in this order. */
        enum class Part { VERSION, LOCK_TIME, INPUTS, OUTPUTS }
    }

    /**
     * An input's signature is missing, doesn't verify, or signs less than the
     * whole transaction (any sighash other than ALL, or DEFAULT for taproot).
     */
    class SignatureInvalid(
        message: String,
        cause: Throwable? = null
    ) : PsbtException(message, cause)
}

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
