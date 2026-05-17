package io.sourlabs.btc.wallet.api

import io.sourlabs.btc.wallet.core.SyncConfig
import io.sourlabs.btc.wallet.core.WalletConfig
import io.sourlabs.btc.wallet.models.Network
import io.sourlabs.btc.wallet.models.Purpose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BitcoinKitBuilderTest {

    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    @Test
    fun builderConstructionDoesNotResolveDefaultSyncConfigForRegtest() {
        // The Builder must not eagerly evaluate SyncConfig.BlockStream.forNetwork(REGTEST),
        // because REGTEST has no public default — that call throws. Evaluating it at
        // construction time would crash before the caller had a chance to call
        // .syncConfig(SyncConfig.CustomApi(...)).
        BitcoinKit.builder(
            WalletConfig.FromMnemonic(
                mnemonic = testMnemonic,
                purpose = Purpose.BIP84,
                network = Network.REGTEST,
            )
        )
        // Reaching here without an exception is the assertion.
    }

    @Test
    fun buildForRegtestWithoutExplicitSyncConfigThrows() {
        val builder = BitcoinKit.builder(
            WalletConfig.FromMnemonic(
                mnemonic = testMnemonic,
                purpose = Purpose.BIP84,
                network = Network.REGTEST,
            )
        )
        // Without .syncConfig(...), build() falls through to the default, which
        // throws for REGTEST. The error message points the caller at the fix.
        assertFailsWith<IllegalArgumentException> {
            builder.build()
        }
    }

    @Test
    fun buildForRegtestWithCustomApiSucceeds() {
        val wallet = BitcoinKit.builder(
            WalletConfig.FromMnemonic(
                mnemonic = testMnemonic,
                purpose = Purpose.BIP84,
                network = Network.REGTEST,
            )
        )
            .syncConfig(SyncConfig.CustomApi(baseUrl = "http://localhost:3000/api"))
            .build()
        assertEquals(Network.REGTEST, wallet.network)
        assertEquals(Purpose.BIP84, wallet.purpose)
    }

    @Test
    fun buildForTestnet4WithoutExplicitSyncConfigSucceeds() {
        // TESTNET4 needs a sync source that actually serves Testnet4 — Blockstream
        // doesn't, Mempool.space does. The Builder's implicit default must route
        // TESTNET4 through MempoolSpace, not crash on
        // SyncConfig.BlockStream.forNetwork(TESTNET4).
        //
        // Anchor for the PR-12 bug where adding the enum value without updating
        // the default fallback turned `BitcoinKit.builder(network = TESTNET4).build()`
        // into a hard crash for callers who didn't pass an explicit syncConfig.
        val wallet = BitcoinKit.builder(
            WalletConfig.FromMnemonic(
                mnemonic = testMnemonic,
                purpose = Purpose.BIP84,
                network = Network.TESTNET4,
            )
        ).build()
        assertEquals(Network.TESTNET4, wallet.network)
    }
}
