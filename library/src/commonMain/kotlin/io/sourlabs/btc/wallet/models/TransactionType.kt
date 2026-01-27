package io.sourlabs.btc.wallet.models

/**
 * Type of transaction from the wallet's perspective.
 */
enum class TransactionType {
    /**
     * Incoming transaction (receiving funds).
     */
    INCOMING,

    /**
     * Outgoing transaction (sending funds to others).
     */
    OUTGOING,

    /**
     * Self-transfer (consolidation or moving between own addresses).
     */
    SELF
}
