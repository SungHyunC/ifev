package com.ifev.app.vehicle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Only an answered capability check can establish that a property is unsupported. */
internal enum class MotionCapability { READABLE, UNSUPPORTED, RETRYABLE_FAILURE }

/** SDK-independent callback bridge. Duplicate and late callbacks cannot complete a newer attempt. */
internal suspend fun awaitMotionCapability(
    timeoutMs: Long = 5_000L,
    onFailure: (Exception) -> Unit,
    onTimeout: () -> Unit,
    check: (onComplete: (Boolean) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
): MotionCapability = withTimeoutOrNull(timeoutMs) {
    val result = CompletableDeferred<MotionCapability>()
    fun failed(error: Exception) {
        if (result.complete(MotionCapability.RETRYABLE_FAILURE)) onFailure(error)
    }
    try {
        try {
            check({ readable ->
                result.complete(if (readable) MotionCapability.READABLE else MotionCapability.UNSUPPORTED)
            }, ::failed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failed(error)
        }
        result.await()
    } finally {
        result.cancel()
    }
} ?: MotionCapability.RETRYABLE_FAILURE.also { onTimeout() }

/**
 * One initial attempt plus at most [maxRetries], independently for each signal. Clear even partially
 * registered listeners before waiting/retrying. The flow owner releases a successful subscription
 * on close; cancellation always propagates to that owner instead of becoming a retry.
 */
internal suspend fun retryMotionSubscription(
    attempt: suspend () -> MotionCapability,
    cleanup: () -> Unit,
    onFailure: (Exception) -> Unit,
    onRetry: (Int) -> Unit = {},
    maxRetries: Int = 3,
    retryDelayMs: Long = 5_000L,
    pause: suspend (Long) -> Unit = { delay(it) },
): MotionCapability {
    require(maxRetries >= 0)
    require(retryDelayMs > 0)
    var retries = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val result = try {
            attempt()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onFailure(error)
            MotionCapability.RETRYABLE_FAILURE
        }
        if (result == MotionCapability.READABLE) return result
        cleanup()
        if (result == MotionCapability.UNSUPPORTED || retries == maxRetries) return result
        retries++
        onRetry(retries)
        pause(retryDelayMs)
    }
}
