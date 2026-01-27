package io.sourlabs.btc.wallet.models

/**
 * BIP purpose constants for HD wallet derivation paths.
 */
enum class Purpose(val value: Int) {
    /**
     * BIP44: Legacy P2PKH addresses (1...)
     * Derivation path: m/44'/coin'/account'/change/index
     */
    BIP44(44),

    /**
     * BIP49: Nested SegWit P2SH-P2WPKH addresses (3...)
     * Derivation path: m/49'/coin'/account'/change/index
     */
    BIP49(49),

    /**
     * BIP84: Native SegWit P2WPKH addresses (bc1q...)
     * Derivation path: m/84'/coin'/account'/change/index
     */
    BIP84(84),

    /**
     * BIP86: Taproot P2TR addresses (bc1p...)
     * Derivation path: m/86'/coin'/account'/change/index
     */
    BIP86(86);

    companion object {
        fun fromValue(value: Int): Purpose? = entries.find { it.value == value }

        /**
         * All standard purposes for wallet restoration scanning.
         */
        val allPurposes: List<Purpose> = entries
    }
}
