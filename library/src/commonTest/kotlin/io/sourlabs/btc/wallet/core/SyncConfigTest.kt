package io.sourlabs.btc.wallet.core

import io.sourlabs.btc.wallet.models.Network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SyncConfigTest {

    @Test
    fun blockStreamForNetworkRegtestThrows() {
        // REGTEST has no public Blockstream endpoint — silently returning the mainnet URL
        // would let a regtest-targeted wallet sync against real Bitcoin. Force the caller
        // to construct SyncConfig.BlockStream(baseUrl = ...) explicitly.
        assertFailsWith<IllegalArgumentException> {
            SyncConfig.BlockStream.forNetwork(Network.REGTEST)
        }
    }

    @Test
    fun mempoolSpaceForNetworkRegtestThrows() {
        assertFailsWith<IllegalArgumentException> {
            SyncConfig.MempoolSpace.forNetwork(Network.REGTEST)
        }
    }

    @Test
    fun blockStreamEnterpriseRegtestThrows() {
        assertFailsWith<IllegalArgumentException> {
            SyncConfig.BlockStream.enterprise(Network.REGTEST, "client-id", "secret")
        }
    }

    @Test
    fun blockStreamForNetworkMainnetReturnsPublicEndpoint() {
        val config = SyncConfig.BlockStream.forNetwork(Network.MAINNET)
        assertEquals(SyncConfig.BlockStream.DEFAULT_MAINNET_URL, config.baseUrl)
    }
}
