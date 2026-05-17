package io.sourlabs.btc.wallet.core

import kotlin.test.Test
import kotlin.test.assertFailsWith

class WalletConfigTest {

    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    @Test
    fun fromMnemonicRequiresPositiveGapLimit() {
        assertFailsWith<IllegalArgumentException> {
            WalletConfig.FromMnemonic(mnemonic = testMnemonic, gapLimit = 0)
        }
    }

    @Test
    fun fromSeedRequiresPositiveGapLimit() {
        assertFailsWith<IllegalArgumentException> {
            WalletConfig.FromSeed(seed = ByteArray(16), gapLimit = 0)
        }
    }

    @Test
    fun fromExtendedPrivateKeyRequiresPositiveGapLimit() {
        assertFailsWith<IllegalArgumentException> {
            WalletConfig.FromExtendedPrivateKey(accountExtendedPrivateKey = "xprv", gapLimit = 0)
        }
    }

    @Test
    fun watchOnlyRequiresPositiveGapLimit() {
        assertFailsWith<IllegalArgumentException> {
            WalletConfig.WatchOnly(extendedPublicKey = "xpub", gapLimit = 0)
        }
    }
}
