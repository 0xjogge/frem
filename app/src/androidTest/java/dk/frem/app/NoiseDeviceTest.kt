package dk.frem.app

import android.Manifest
import android.os.PowerManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dk.frem.app.hearing.*
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NoiseDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun calibrationAndScreenOffCaptureReachHistoryWithoutSavingAudio() = runBlocking {
        val app: FremApplication = ApplicationProvider.getApplicationContext()
        val repository = app.noise
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        val sources = Channel<TestSource>(Channel.UNLIMITED)
        val key = "instrumentation/built-in/48000"
        app.database.noiseDao().deleteCalibration(key)
        repository.setSourceFactoryForTesting { TestSource().also { sources.trySend(it) } }
        val ids = mutableListOf<String>()
        try {
            compose.onNodeWithTag("navigation_HEALTH").performClick()
            compose.onNodeWithTag("health_list").performScrollToNode(hasText("Hearing exposure"))
            compose.onNodeWithText("Hearing exposure").performClick()
            compose.onNodeWithTag("noise_start").performClick()
            val source = withTimeout(10_000) { sources.receive() }
            ids += repository.live.value.session!!.id
            repeat(11) { source.frames.send(NoiseFrame(tone(), key, System.currentTimeMillis())) }
            await("calibration window") { repository.live.value.calibrationReady }
            assertNull(repository.live.value.session!!.leqDbA)
            compose.onNodeWithTag("hearing_list").performScrollToNode(hasTestTag("noise_reference"))
            compose.onNodeWithTag("noise_reference").performTextInput("85")
            compose.onNodeWithTag("hearing_list").performScrollToNode(hasTestTag("noise_calibrate"))
            compose.onNodeWithTag("noise_calibrate").performClick()
            await("saved calibration") {
                repository.live.value.message?.startsWith("Calibration saved") == true
            }
            repository.stop()
            await("first finish") { repository.live.value.session?.state == "FINISHED" }
            assertNull(app.database.noiseDao().session(ids.last())!!.dosePercent)

            compose.onNodeWithTag("hearing_list").performScrollToNode(hasTestTag("noise_start"))
            compose.onNodeWithTag("noise_start").performClick()
            val calibrated = withTimeout(10_000) { sources.receive() }
            ids += repository.live.value.session!!.id
            automation.executeShellCommand("input keyevent 223").close()
            await { !app.getSystemService(PowerManager::class.java).isInteractive }
            repeat(7) {
                calibrated.frames.send(NoiseFrame(tone(), key, System.currentTimeMillis()))
            }
            await("screen-off samples") { repository.live.value.session!!.measuredMillis >= 6000 }
            assertFalse(app.getSystemService(PowerManager::class.java).isInteractive)
            assertTrue(app.database.noiseDao().session(ids.last())!!.measuredMillis > 0)
            repository.stop()
            await { repository.live.value.session?.state == "FINISHED" }
            val saved = app.database.noiseDao().session(ids.last())!!
            assertEquals(85.0, saved.leqDbA!!, .1)
            assertTrue(saved.dosePercent!! > 0)
            assertEquals(1000L, saved.unmeasuredMillis)
        } finally {
            automation.executeShellCommand("input keyevent 224").close()
            if (repository.live.value.session?.state == "RECORDING") {
                repository.stop()
                await { repository.live.value.session?.state != "RECORDING" }
            }
            repository.setSourceFactoryForTesting(null)
            ids.forEach { repository.delete(it) }
            app.database.noiseDao().deleteCalibration(key)
        }
    }

    private suspend fun await(label: String = "condition", predicate: () -> Boolean) {
        val success =
            withTimeoutOrNull(15_000) {
                while (!predicate()) delay(20)
                true
            } ?: false
        val app: FremApplication = ApplicationProvider.getApplicationContext()
        assertTrue("$label: ${app.noise.live.value}", success)
    }

    private fun tone() =
        ShortArray(48_000) { (sin(2 * PI * 1000 * it / 48_000) * 3000).toInt().toShort() }

    private class TestSource : NoiseSource {
        val frames = Channel<NoiseFrame>(Channel.UNLIMITED)

        override fun frames(): Flow<NoiseFrame> = frames.receiveAsFlow()

        override fun close() {
            frames.close()
        }
    }
}
