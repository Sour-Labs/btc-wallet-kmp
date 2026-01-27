package io.sourlabs.btc.wallet.models

/**
 * Wallet balance information broken down by spendability.
 * All values are in satoshis.
 */
data class BalanceInfo(
    /**
     * Balance that can be spent immediately.
     * Includes confirmed UTXOs meeting the confirmation threshold.
     */
    val spendable: Long,

    /**
     * Balance pending confirmations.
     * Includes incoming transactions not yet confirmed.
     */
    val unconfirmed: Long,

    /**
     * Balance that is locked and cannot be spent.
     * Includes time-locked outputs or plugin-locked UTXOs.
     */
    val locked: Long
) {
    /**
     * Total balance (sum of all categories).
     */
    val total: Long
        get() = spendable + unconfirmed + locked

    companion object {
        val ZERO = BalanceInfo(
            spendable = 0L,
            unconfirmed = 0L,
            locked = 0L
        )
    }
}
