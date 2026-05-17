package io.sourlabs.btc.wallet.models

/**
 * Bitcoin script types supported by the wallet.
 */
enum class ScriptType {
    /**
     * Pay-to-Public-Key-Hash (Legacy)
     * Addresses start with '1' on mainnet
     */
    P2PKH,

    /**
     * Generic Pay-to-Script-Hash. Returned by [io.sourlabs.btc.wallet.keys.AddressConverter.parseAddress]
     * for any `3...` address, since the scriptPubKey alone (`OP_HASH160 <h> OP_EQUAL`)
     * doesn't reveal whether the redeem script is P2WPKH, multisig, time-locked, or
     * something else — that information is only on the spending side.
     *
     * The wallet does not generate this type itself; [P2SH_P2WPKH] is used for nested
     * SegWit outputs the wallet owns.
     */
    P2SH,

    /**
     * Pay-to-Script-Hash wrapping P2WPKH (Nested SegWit)
     * Addresses start with '3' on mainnet.
     *
     * Used for wallet-owned keys derived under BIP-49. External `3...` destinations
     * parsed from an address resolve to the generic [P2SH] instead, since the wrap
     * isn't inspectable from the scriptPubKey alone.
     */
    P2SH_P2WPKH,

    /**
     * Pay-to-Witness-Public-Key-Hash (Native SegWit v0)
     * Addresses start with 'bc1q' on mainnet
     */
    P2WPKH,

    /**
     * Pay-to-Taproot (SegWit v1)
     * Addresses start with 'bc1p' on mainnet
     */
    P2TR;

    companion object {
        /**
         * Get the script type for a given BIP purpose.
         */
        fun fromPurpose(purpose: Purpose): ScriptType = when (purpose) {
            Purpose.BIP44 -> P2PKH
            Purpose.BIP49 -> P2SH_P2WPKH
            Purpose.BIP84 -> P2WPKH
            Purpose.BIP86 -> P2TR
        }
    }
}
