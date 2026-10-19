package dk.frem.app

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dk.frem.app.data.*
import dk.frem.app.recording.ActivityDayMetrics
import dk.frem.app.ui.*
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class NativeMetricHistoryDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun nativeMeasurementsReachHistoryButImportedZeroesKeepPriority() {
        val metric = mutableStateOf(Metric.CADENCE)
        val date = LocalDate.now()
        val data =
            AppData(
                listOf(DaySummary(date.toString(), floors = 0.0)),
                emptyList(),
                emptyList(),
                emptyList(),
                Preferences(),
                emptyList(),
                emptySet(),
                null,
                date,
                nativeMetrics =
                    listOf(
                        ActivityDayMetrics(
                            "walk",
                            date.toString(),
                            cadenceSteps = 120.0,
                            cadenceMillis = 60_000,
                            floorAscentMeters = 9.0,
                        ),
                    ),
            )
        compose.setContent { FremTheme { Surface { MetricDetail(metric.value, data, {}) } } }
        compose.onNodeWithTag("metric_detail_value").assertTextEquals("120")
        compose
            .onNodeWithTag("metric_history_list")
            .performScrollToNode(hasText("Frem recordings · moving step rate"))
        compose.onNodeWithText("Frem recordings · moving step rate").assertIsDisplayed()
        compose.runOnIdle { metric.value = Metric.FLOORS }
        compose.onNodeWithTag("metric_history_list").performScrollToIndex(0)
        compose.onNodeWithTag("metric_detail_value").assertTextEquals("0")
        compose.onNodeWithText("Frem recordings · barometer + steps").assertDoesNotExist()
    }
}
