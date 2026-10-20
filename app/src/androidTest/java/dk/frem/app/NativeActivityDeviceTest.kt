package dk.frem.app

import android.Manifest
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dk.frem.app.recording.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeActivityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun hardwareStepTimesReachDurableMetricsAndPauseFlushesTheTail() = runBlocking {
        val app: FremApplication = ApplicationProvider.getApplicationContext()
        val repo = app.recording
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACTIVITY_RECOGNITION,
            )
            .forEach { automation.grantRuntimePermission(app.packageName, it) }
        repo.awaitReady()
        if (app.database.recordingDao().activeSession() != null) repo.discardAndAwait()
        val ready = CompletableDeferred<TestSensors>()
        repo.setSensorSourceFactoryForTesting { TestSensors().also { ready.complete(it) } }
        repo.setLocationSourceFactoryForTesting { QuietLocation() }
        var id: String? = null
        try {
            id = repo.start(ActivityType.RUNNING)
            val sensors = withTimeout(10_000) { ready.await() }
            val first = SystemClock.elapsedRealtimeNanos()
            SystemClock.sleep(5_000)
            (0L..12).forEach {
                val elapsed = first + it * 400_000_000
                val pressure = 1013.25 * Math.pow(1 - it * .5 / 44330, 1 / .190294957)
                sensors.channel.send(RecordingSensorEvent.Pressure(elapsed, pressure))
            }
            // Independent sensor FIFOs may deliver the entire pressure batch before the steps.
            (0L..12).forEach {
                sensors.channel.send(RecordingSensorEvent.Step(first + it * 400_000_000))
            }
            repo.pauseAndAwait()
            val saved = app.database.recordingDao().getMetricDays(id).single()
            assertEquals(13L, saved.steps)
            assertEquals(150.0, saved.cadence!!, .01)
            assertTrue(saved.vigorousMillis > 0)
            assertEquals(0L, saved.moderateMillis)
            assertTrue(saved.ascentMeters!! >= 3)
            assertTrue(saved.floors!! >= 1)
            assertEquals("Barometer", saved.elevationSource)
            assertNull(repo.live.value.cadence)
            assertTrue(sensors.closed)
            repo.finishAndAwait()
            assertEquals(saved, app.database.recordingDao().getMetricDays(id).single())
            repo.delete(id)
            assertTrue(app.database.recordingDao().getMetricDays(id).isEmpty())
        } finally {
            if (app.database.recordingDao().activeSession() != null) repo.discardAndAwait()
            id?.let { repo.delete(it) }
            repo.setSensorSourceFactoryForTesting(null)
            repo.resetLocationSourceFactoryForTesting()
        }
    }

    private class TestSensors : RecordingSensorSource {
        val channel = Channel<RecordingSensorEvent>(Channel.UNLIMITED)
        var closed = false

        override fun events(): Flow<RecordingSensorEvent> = channel.receiveAsFlow()

        override suspend fun flush() {
            val done = CompletableDeferred<Unit>()
            channel.send(RecordingSensorEvent.Barrier(done))
            done.await()
        }

        override fun close() {
            closed = true
            channel.close()
        }
    }

    private class QuietLocation : RecordingLocationSource {
        val channel = Channel<RecordingLocationEvent>(Channel.UNLIMITED)

        override fun events(): Flow<RecordingLocationEvent> = channel.receiveAsFlow()

        override fun close() {
            channel.close()
        }
    }
}
