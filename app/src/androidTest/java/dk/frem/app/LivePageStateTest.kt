package dk.frem.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dk.frem.app.data.*
import dk.frem.app.ui.*
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic values stay in composition; these tests never write health or user data. */
class LivePageStateTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun settingsSwitchUpdatesOnTheSameRetainedPageInBothDirections() {
        val preferences = mutableStateOf(Preferences())
        compose.setContent {
            val snapshot = preferences.value
            val navigation = rememberFremNavigation()
            FremTheme(snapshot) {
                FremNavDisplay(navigation) {
                    Surface {
                        SettingsSwitch("Imperial units", "Live units", snapshot.imperial) { checked
                            ->
                            preferences.value = preferences.value.copy(imperial = checked)
                        }
                    }
                }
            }
        }
        val toggle = compose.onNode(isToggleable())
        toggle.assertIsOff().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        toggle.assertIsOn()
        toggle.performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        toggle.assertIsOff()
    }

    @Test
    fun incomingStepsGoalsAndUnitChangesRecomposeAnAlreadyVisiblePage() {
        val today = LocalDate.now()
        val day = mutableStateOf(DaySummary(today.toString(), steps = 1200, distance = 1609.344))
        val preferences = mutableStateOf(Preferences())
        var navigation: FremNavigation? = null
        compose.setContent {
            val snapshot =
                AppData(
                    listOf(day.value),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    preferences.value,
                    emptyList(),
                    setOf("test"),
                    null,
                    today,
                )
            val state = rememberFremNavigation()
            navigation = state
            FremTheme(snapshot.preferences) {
                FremNavDisplay(state) {
                    Surface {
                        Column {
                            Text("${snapshot.day!!.steps} / ${snapshot.preferences.stepGoal}")
                            Text("Unit: ${Metric.DISTANCE.unit(snapshot.preferences)}")
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("1200 / 8000").assertExists()
        compose.runOnUiThread { day.value = day.value.copy(steps = 2450) }
        compose.onNodeWithText("2450 / 8000").assertExists()
        compose.runOnUiThread {
            preferences.value = preferences.value.copy(stepGoal = 10000, imperial = true)
        }
        compose.onNodeWithText("2450 / 10000").assertExists()
        compose.onNodeWithText("Unit: mi").assertExists()
        assertEquals(listOf(FremDestination.TODAY), navigation!!.backStack)
    }

    @Test
    fun actionsOnRetainedPagesUseTheLatestData() {
        val goal = mutableIntStateOf(8000)
        var usedGoal: Int? = null
        compose.setContent {
            val snapshot = goal.intValue
            val navigation = rememberFremNavigation()
            FremTheme {
                FremNavDisplay(navigation) {
                    androidx.compose.material3.Button(onClick = { usedGoal = snapshot }) {
                        Text("Use current goal")
                    }
                }
            }
        }
        compose.runOnUiThread { goal.intValue = 10000 }
        compose.onNodeWithText("Use current goal").performSemanticsAction(
            SemanticsActions.OnClick,
        ) {
            it()
        }
        assertEquals(10000, usedGoal)
    }

    @Test
    fun selectedChartPeriodUpdatesWhenNewStepsArrive() {
        val today = LocalDate.now()
        val day = mutableStateOf(DaySummary(today.toString(), steps = 1200))
        compose.setContent {
            val data =
                AppData(
                    listOf(day.value),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    Preferences(),
                    emptyList(),
                    emptySet(),
                    null,
                    today,
                )
            FremTheme {
                Surface(Modifier.fillMaxSize()) { MetricDetail(Metric.STEPS, data, {}) }
            }
        }
        compose.onNodeWithTag("history_chart").performTouchInput {
            click(Offset(width * .93f, height / 2f))
        }
        compose.onNodeWithText("Recorded total").assertDoesNotExist()
        compose.onNodeWithTag("metric_detail_value").assertTextEquals(number(1200))
        compose.runOnUiThread { day.value = day.value.copy(steps = 2800) }
        compose.onNodeWithTag("metric_detail_value").assertTextEquals(number(2800))
    }
}
