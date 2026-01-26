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
     * Pay-to-Script-Hash wrapping P2WPKH (Nested SegWit)
     * Addresses start with '3' on mainnet
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
