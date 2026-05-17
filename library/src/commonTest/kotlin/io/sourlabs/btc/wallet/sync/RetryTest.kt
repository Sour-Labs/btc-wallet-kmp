package io.sourlabs.btc.wallet.sync

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/**
 * Anchors PR-03 (audit finding H11). Tests the retry helper directly rather than
 * stubbing HTTP responses through Ktor's MockEngine — keeps the dependency surface
 * minimal and the tests focused on the retry semantics rather than the HTTP plumbing.
 */
class RetryTest {

    @Test
    fun returnsResultOnFirstSuccess() = runTest {
        val result = withRetry { 42 }
        assertEquals(42, result)
    }

    @Test
    fun retriesUntilSuccess() = runTest {
        var attempts = 0
        val result = withRetry(initialDelayMs = 1, jitterFraction = 0.0) {
            attempts++
            if (attempts < 3) throw RuntimeException("flaky")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, attempts)
    }

    @Test
    fun throwsAfterMaxAttempts() = runTest {
        var attempts = 0
        assertFailsWith<RuntimeException> {
            withRetry(maxAttempts = 3, initialDelayMs = 1, jitterFraction = 0.0) {
                attempts++
                throw RuntimeException("permanent")
            }
        }
        assertEquals(3, attempts)
    }

    @Test
    fun doesNotRetryWhenIsRetriablePredicateReturnsFalse() = runTest {
        var attempts = 0
        assertFailsWith<RuntimeException> {
            withRetry(
                initialDelayMs = 1,
                jitterFraction = 0.0,
                isRetriable = { false },
            ) {
                attempts++
                throw RuntimeException("4xx-like, no retry")
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun propagatesCancellationWithoutRetrying() = runTest {
        var attempts = 0
        assertFailsWith<CancellationException> {
            withRetry(initialDelayMs = 1, jitterFraction = 0.0) {
                attempts++
                throw CancellationException("upstream cancelled")
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun rejectsZeroMaxAttempts() = runTest {
        assertFailsWith<IllegalArgumentException> {
            withRetry(maxAttempts = 0) { 1 }
        }
    }
}
