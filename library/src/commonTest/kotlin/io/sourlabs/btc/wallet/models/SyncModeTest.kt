package io.sourlabs.btc.wallet.models

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncModeTest {

    @Test
    fun exposes_three_distinct_modes() {
        val modes: Set<SyncMode> = setOf(
            SyncMode.OneShot,
            SyncMode.Continuous,
            SyncMode.IncrementalOnly,
        )
        assertEquals(3, modes.size)
    }
}
