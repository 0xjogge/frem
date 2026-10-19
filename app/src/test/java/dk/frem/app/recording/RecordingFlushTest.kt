package dk.frem.app.recording

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class RecordingFlushTest {
    @Test
    fun immediateAndAbsentSourcesRemainCompatibleWithTheDefaultFlush() = runBlocking {
        assertTrue(flushRecordingSource(null))
        assertTrue(flushRecordingSource(ImmediateSource()))
    }

    @Test
    fun bufferedFakeFactorySeamWaitsUntilEveryPrecedingPointHasFinishedProcessing() = runBlocking {
        val tail = listOf(fix(1), fix(2))
        val source = BufferedSource(tail)
        val processed = mutableListOf<RecordingFix>()
        val allowProcessing = CompletableDeferred<Unit>()
        val collector =
            launch(start = CoroutineStart.UNDISPATCHED) {
                source.events().collect { event ->
                    when (event) {
                        is RecordingLocationEvent.Fix -> {
                            allowProcessing.await()
                            yield()
                            processed += event.fix
                        }
                        is RecordingLocationEvent.Barrier -> event.completion.complete(Unit)
                        is RecordingLocationEvent.Availability -> Unit
                    }
                }
            }
        val flushing = async { flushRecordingSource(source) }
        source.barrierSent.await()
        yield()
        assertFalse(flushing.isCompleted)
        assertTrue(processed.isEmpty())
        allowProcessing.complete(Unit)
        assertTrue(withTimeout(5_000) { flushing.await() })
        assertEquals(tail, processed)
        assertFalse(source.closed)
        source.close()
        collector.join()
    }

    @Test
    fun anUnresponsiveProviderCannotHoldAStopForever() = runBlocking {
        val cancelled = AtomicBoolean(false)
        val source =
            object : ImmediateSource() {
                override suspend fun flush() {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.set(true)
                    }
                }
            }
        assertFalse(withTimeout(5_000) { flushRecordingSource(source, timeoutMillis = 20) })
        assertTrue(cancelled.get())
        // The caller can now close/persist its stop, independently of provider success.
        source.close()
        assertTrue(source.closed)
    }

    @Test
    fun aProviderFailureStillAllowsTheCallerToCloseAndPersistItsStop() = runBlocking {
        val source =
            object : ImmediateSource() {
                override suspend fun flush() {
                    throw IOException("Provider unavailable")
                }
            }
        assertFalse(flushRecordingSource(source))
        source.close()
        assertTrue(source.closed)
    }

    @Test
    fun closingABufferedSourceUnblocksAnUnacknowledgedBarrier() = runBlocking {
        val source = BufferedSource(listOf(fix(1)))
        val flushing = async { flushRecordingSource(source) }
        source.barrierSent.await()
        source.close()
        assertFalse(withTimeout(5_000) { flushing.await() })
        assertTrue(flushRecordingSource(source))
    }

    @Test
    fun actualCallerCancellationIsNotConvertedToAProviderFailure() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val afterFlush = AtomicBoolean(false)
        val source =
            object : ImmediateSource() {
                override suspend fun flush() {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        val caller = launch {
            flushRecordingSource(source)
            afterFlush.set(true)
        }
        entered.await()
        caller.cancelAndJoin()
        assertFalse(afterFlush.get())
    }

    private open class ImmediateSource : RecordingLocationSource {
        var closed = false
            private set

        override fun events(): Flow<RecordingLocationEvent> = emptyFlow()

        override fun close() {
            closed = true
        }
    }

    /**
     * Instrumentation can use this same protocol with
     * repository.setLocationSourceFactoryForTesting.
     */
    private class BufferedSource(private val tail: List<RecordingFix>) : ImmediateSource() {
        private val events = Channel<RecordingLocationEvent>(Channel.UNLIMITED)
        private var barrier: CompletableDeferred<Unit>? = null
        val barrierSent = CompletableDeferred<Unit>()

        override fun events(): Flow<RecordingLocationEvent> = events.receiveAsFlow()

        override suspend fun flush() {
            if (closed) return
            val completion = CompletableDeferred<Unit>()
            barrier = completion
            tail.forEach { events.send(RecordingLocationEvent.Fix(it)) }
            events.send(RecordingLocationEvent.Barrier(completion))
            barrierSent.complete(Unit)
            completion.await()
        }

        override fun close() {
            super.close()
            barrier?.cancel()
            events.close()
        }
    }

    private fun fix(second: Long) =
        RecordingFix(
            recordedAt = 1_700_000_000_000 + second * 1_000,
            elapsedRealtimeNanos = second * 1_000_000_000,
            latitude = 55.0,
            longitude = 12.0,
            accuracyMeters = 3f,
        )
}
