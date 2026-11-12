package com.ifev.app.vehicle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MotionCapabilityTest {
    @Test fun explicitCapabilityAnswerDistinguishesReadableFromUnsupported() = runBlocking {
        for (readable in listOf(true, false)) {
            val result = awaitMotionCapability(onFailure = { throw it }, onTimeout = { fail("timeout") }) { done, _ ->
                done(readable)
            }
            assertEquals(if (readable) MotionCapability.READABLE else MotionCapability.UNSUPPORTED, result)
        }
    }

    @Test fun synchronousAndCallbackFailuresAreRetryable() = runBlocking {
        for (synchronous in listOf(true, false)) {
            val error = IllegalStateException("service temporarily unavailable")
            val failures = mutableListOf<Exception>()
            val result = awaitMotionCapability(onFailure = { failures += it }, onTimeout = { fail("timeout") }) { _, failed ->
                if (synchronous) throw error else failed(error)
            }
            assertEquals(MotionCapability.RETRYABLE_FAILURE, result)
            assertEquals(listOf(error), failures)
        }
    }

    @Test fun timeoutIsRetryableAndLateResponseCannotContaminateNextAttempt() = runBlocking {
        lateinit var late: (Boolean) -> Unit
        var timeouts = 0
        val result = awaitMotionCapability(timeoutMs = 30, onFailure = { throw it }, onTimeout = { timeouts++ }) { done, _ ->
            late = done
        }
        assertEquals(MotionCapability.RETRYABLE_FAILURE, result)
        assertEquals(1, timeouts)
        val next = awaitMotionCapability(onFailure = { throw it }, onTimeout = { fail("timeout") }) { done, _ ->
            late(false)
            done(true)
        }
        assertEquals(MotionCapability.READABLE, next)
    }

    @Test fun duplicateCallbacksKeepTheFirstAnswer() = runBlocking {
        val result = awaitMotionCapability(onFailure = { fail("duplicate failure") }, onTimeout = { fail("timeout") }) { done, failed ->
            done(true)
            done(false)
            failed(IllegalStateException("late failure"))
        }
        assertEquals(MotionCapability.READABLE, result)
    }

    @Test fun failedRegistrationIsCleanedBeforeFiveSecondRetryAndRecovery() = runBlocking {
        val events = mutableListOf<String>()
        var attempts = 0
        var listeners = 0
        val result = retryMotionSubscription(
            attempt = {
                events += "attempt"
                assertEquals(0, listeners)
                listeners++
                if (++attempts == 1) throw IllegalStateException("partially registered")
                MotionCapability.READABLE
            },
            cleanup = { events += "cleanup"; listeners = 0 },
            onFailure = { events += "failure" },
            pause = { events += "wait:$it" },
        )
        assertEquals(MotionCapability.READABLE, result)
        assertEquals(listOf("attempt", "failure", "cleanup", "wait:5000", "attempt"), events)
        assertEquals(1, listeners) // Successful listener remains owned by the flow until close.
    }

    @Test fun unsupportedPropertyDoesNotRetry() = runBlocking {
        var attempts = 0
        var cleanups = 0
        val result = retryMotionSubscription(
            attempt = { attempts++; MotionCapability.UNSUPPORTED },
            cleanup = { cleanups++ },
            onFailure = { throw it },
            pause = { fail("unsupported capability must not retry") },
        )
        assertEquals(MotionCapability.UNSUPPORTED, result)
        assertEquals(1, attempts)
        assertEquals(1, cleanups)
    }

    @Test fun transientFailuresStopAfterThreeRetries() = runBlocking {
        var attempts = 0
        var cleanups = 0
        val waits = mutableListOf<Long>()
        val result = retryMotionSubscription(
            attempt = { attempts++; MotionCapability.RETRYABLE_FAILURE },
            cleanup = { cleanups++ },
            onFailure = { throw it },
            pause = { waits += it },
        )
        assertEquals(MotionCapability.RETRYABLE_FAILURE, result)
        assertEquals(4, attempts)
        assertEquals(4, cleanups)
        assertEquals(listOf(5_000L, 5_000L, 5_000L), waits)
    }

    @Test fun cancellationWhileWaitingDoesNotRetry() = runBlocking {
        var attempts = 0
        var cleanups = 0
        val waiting = CompletableDeferred<Unit>()
        val job = launch {
            retryMotionSubscription(
                attempt = { attempts++; MotionCapability.RETRYABLE_FAILURE },
                cleanup = { cleanups++ },
                onFailure = { fail("cancellation is not a failure") },
                pause = { waiting.complete(Unit); awaitCancellation() },
            )
        }
        waiting.await()
        job.cancelAndJoin()
        assertEquals(1, attempts)
        assertEquals(1, cleanups)
    }

    @Test fun cancellationDuringCapabilityCheckPropagates() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val result = async {
            awaitMotionCapability(onFailure = { fail("cancellation is not a failure") }, onTimeout = { fail("not a timeout") }) { _, _ ->
                started.complete(Unit)
            }
        }
        started.await()
        result.cancel()
        try {
            result.await()
            fail("must propagate cancellation")
        } catch (_: CancellationException) {
            assertTrue(result.isCancelled)
        }
    }
}
