package dk.frem.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dk.frem.app.ui.FremTheme
import dk.frem.app.ui.HistoryChart
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HistoryChartDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun previewBarsAndEmptySpaceOpenTheContainingCard() {
        var opened = 0
        compose.setContent {
            FremTheme {
                Card(onClick = { opened++ }, modifier = Modifier.testTag("preview")) {
                    Column(Modifier.padding(20.dp)) {
                        Text("This week")
                        HistoryChart(listOf("Mon" to 100.0, "Tue" to null, "Wed" to 20.0))
                    }
                }
            }
        }
        val chart = compose.onNodeWithTag("history_chart", useUnmergedTree = true)
        chart.performTouchInput { click(percentOffset(.16f, .8f)) }
        compose.runOnIdle { assertEquals(1, opened) }
        chart.performTouchInput { click(percentOffset(.5f, .1f)) }
        compose.runOnIdle { assertEquals(2, opened) }
        compose.onNodeWithTag("preview").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(center.x, 8f))
        }
        compose.runOnIdle { assertEquals(3, opened) }
    }

    @Test
    fun detailSelectsTheTappedPeriodAndUsesTheLatestCallback() {
        val version = mutableStateOf(1)
        var selection = -1
        var opened = 0
        compose.setContent {
            val currentVersion = version.value
            FremTheme {
                Card(onClick = { opened++ }) {
                    HistoryChart(
                        listOf("Mon" to 100.0, "Tue" to null, "Wed" to 20.0),
                        onSelect = { selection = currentVersion * 10 + it },
                    )
                }
            }
        }
        val chart = compose.onNodeWithTag("history_chart", useUnmergedTree = true)
        chart.performTouchInput { click(percentOffset(.5f, .5f)) }
        compose.runOnIdle {
            assertEquals(11, selection)
            version.value = 2
        }
        chart.performTouchInput { click(percentOffset(.99f, .5f)) }
        compose.runOnIdle {
            assertEquals(22, selection)
            assertEquals(0, opened)
        }
    }
}
