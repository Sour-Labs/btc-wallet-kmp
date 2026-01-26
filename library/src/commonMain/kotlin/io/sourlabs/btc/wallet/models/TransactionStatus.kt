package io.sourlabs.btc.wallet.models

/**
 * Status of a wallet transaction.
 */
enum class TransactionStatus {
    /**
     * Transaction created but not yet broadcast.
     */
    PENDING,

    /**
     * Transaction has been broadcast to the network.
     */
    RELAYED,

    /**
     * Transaction is included in a block.
     */
    CONFIRMED,

    /**
     * Transaction broadcast failed.
     */
    FAILED,

    /**
     * Transaction was invalidated (double-spent or rejected).
     */
    INVALID
}
