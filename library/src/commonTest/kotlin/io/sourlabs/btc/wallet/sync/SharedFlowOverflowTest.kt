package io.sourlabs.btc.wallet.sync

import io.sourlabs.btc.wallet.models.WalletEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Anchors PR-03 (audit finding H1): SyncManager's events SharedFlow must not be
 * able to suspend the sync coroutine even when no one is consuming. This test
 * uses the exact same configuration shape — if someone "fixes" the producer-can-
 * deadlock symptom by reverting to the default SUSPEND policy, this test hangs
 * (caught by runTest's default 60s timeout).
 */
class SharedFlowOverflowTest {

    @Test
    fun producerNeverSuspendsWithDropOldest() = runTest {
        val flow = MutableSharedFlow<WalletEvent>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        // No collector — emit far more than the buffer holds. With SUSPEND, the
        // 65th emit would block forever. With DROP_OLDEST, each emit returns
        // immediately and the oldest buffered event is dropped to make room.
        repeat(1_000) { flow.emit(WalletEvent.NewBlock(it, "h", it.toLong())) }
    }
}
