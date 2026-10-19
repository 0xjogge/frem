package dk.frem.app

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.fitness.data.*
import dk.frem.app.data.*
import dk.frem.app.domain.mergePhoneActivity
import dk.frem.app.health.PhoneRecorder
import dk.frem.app.health.summarizePhoneDay
import dk.frem.app.ui.*
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PhoneDistanceDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    // Use the SDK's real integer and float values, not string stand-ins.
    @Suppress("DEPRECATION")
    private fun dataSet(type: DataType, vararg values: Number): LocalDataSet {
        val source =
            DataSource.Builder()
                .setDataType(type)
                .setType(DataSource.TYPE_DERIVED)
                .setAppPackageName(context)
                .build()
        val builder = DataSet.builder(source)
        val start = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(6)
        val interval = TimeUnit.MINUTES.toMillis(15)
        values.forEachIndexed { index, value ->
            val point =
                DataPoint.builder(source)
                    .setTimeInterval(
                        start + index * interval,
                        start + (index + 1) * interval,
                        TimeUnit.MILLISECONDS,
                    )
            if (type == DataType.TYPE_STEP_COUNT_DELTA) point.setIntValues(value.toInt())
            else point.setFloatValues(value.toFloat())
            builder.add(point.build())
        }
        return LocalDataSet(builder.build())
    }

    @Test
    fun sdkDistanceReachesRoomAndHistoryWhileHealthStepsArePreserved(): Unit = runBlocking {
        val today = LocalDate.now()
        val phone =
            summarizePhoneDay(
                today,
                listOf(
                    dataSet(DataType.TYPE_STEP_COUNT_DELTA, 1200, 2400),
                    dataSet(DataType.TYPE_DISTANCE_DELTA, 1000.25, 609.094),
                ),
            )
        assertEquals(3600L, phone.steps)
        assertEquals(1609.344, phone.distance!!, .001)
        val db = Room.inMemoryDatabaseBuilder(context, FremDatabase::class.java).build()
        try {
            val health = DaySummary(today.toString(), steps = 8500)
            db.dao().putDays(listOf(mergePhoneActivity(health, phone, true, true)))
            val saved = db.dao().day(today.toString())!!
            assertEquals(8500L, saved.steps)
            assertEquals(1609.344, saved.distance!!, .001)
            assertEquals("Phone recording", saved.distanceSource)
            val preferences = mutableStateOf(Preferences())
            compose.setContent {
                FremTheme {
                    Surface(Modifier.fillMaxSize()) {
                        MetricDetail(
                            Metric.DISTANCE,
                            AppData(
                                listOf(saved),
                                emptyList(),
                                emptyList(),
                                emptyList(),
                                preferences.value,
                                emptyList(),
                                emptySet(),
                                null,
                                today,
                            ),
                            {},
                        )
                    }
                }
            }
            compose.onNodeWithTag("metric_detail_value").assertTextEquals("1.6")
            compose.onNodeWithText("Estimated total").assertIsDisplayed()
            compose.onNodeWithText("km").assertIsDisplayed()
            compose.runOnIdle { preferences.value = Preferences(imperial = true) }
            compose.onNodeWithTag("metric_detail_value").assertTextEquals("1.0")
            compose.onNodeWithText("mi").assertIsDisplayed()
        } finally {
            db.close()
        }
    }

    @Test
    fun emptySdkDistanceRemainsUnknownButRecordedZeroIsKept() {
        val today = LocalDate.now()
        val stepsOnly =
            summarizePhoneDay(
                today,
                listOf(
                    dataSet(DataType.TYPE_STEP_COUNT_DELTA, 1000),
                    dataSet(DataType.TYPE_DISTANCE_DELTA),
                ),
            )
        assertEquals(1000L, stepsOnly.steps)
        assertNull(stepsOnly.distance)
        assertEquals(
            0.0,
            summarizePhoneDay(today, listOf(dataSet(DataType.TYPE_DISTANCE_DELTA, 0.0))).distance!!,
            0.0,
        )
    }

    @Test
    fun missingDistanceOffersPhoneRecordingWithoutOpeningHealthConnect() {
        var enabled = false
        var connected = false
        compose.setContent {
            FremTheme {
                Surface(Modifier.fillMaxSize()) {
                    MetricDetail(
                        Metric.DISTANCE,
                        AppData(
                            listOf(DaySummary(LocalDate.now().toString(), steps = 8500)),
                            emptyList(),
                            emptyList(),
                            emptyList(),
                            Preferences(),
                            emptyList(),
                            emptySet(),
                            null,
                            LocalDate.now(),
                        ),
                        onConnect = { connected = true },
                        onPhone = { enabled = true },
                    )
                }
            }
        }
        compose
            .onNodeWithTag("metric_history_list")
            .performScrollToNode(hasText("Enable phone distance", substring = true))
        compose
            .onNode(hasClickAction() and hasText("Enable phone distance", substring = true))
            .performClick()
        assertTrue(enabled)
        assertFalse(connected)
    }

    @Test
    fun liveGooglePlayServicesSubscriptionsAndDailyQueriesSucceed() = runBlocking {
        val recorder = PhoneRecorder(context)
        assumeTrue("Grant physical activity access to exercise live recording", recorder.allowed)
        val days = withTimeout(60000) { recorder.read() }
        assertEquals(10, days.size)
        assertEquals(LocalDate.now().toString(), days.last().date)
        assertTrue(days.all { it.source == "Phone recording" })
        assertTrue(days.all { it.distance == null || it.distance!! >= 0.0 })
    }
}
