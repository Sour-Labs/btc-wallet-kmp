package io.sourlabs.btc.wallet.models

/**
 * Information about a block in the blockchain.
 */
data class BlockInfo(
    /**
     * Block height.
     */
    val height: Int,

    /**
     * Block hash in hex.
     */
    val hash: String,

    /**
     * Block timestamp in seconds since epoch.
     */
    val timestamp: Long
)
