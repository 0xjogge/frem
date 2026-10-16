package dk.frem.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventInput
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsLivesInBottomNavigationWithoutTheOldHeader() {
        compose.onNodeWithText("frem ↗").assertDoesNotExist()
        compose.onNodeWithContentDescription("Refresh health data").assertDoesNotExist()
        clickTab("SETTINGS").assertIsSelected()
        compose.onNodeWithTag("settings_title").assertIsDisplayed()
        assertEquals(
            0f,
            compose.onNodeWithTag("settings_list").fetchSemanticsNode().boundsInRoot.top,
            1f,
        )
        capture("settings-navigation")
        clickTab("TODAY")
        capture("today-navigation")
    }

    @Test
    fun predictiveBackPreviewsThePreviousPageAndCanCancelOrComplete() {
        val input = TestBackInput()
        compose.runOnUiThread { compose.activity.navigationEventDispatcher.addInput(input) }
        try {
            clickTab("BODY")
            compose.waitForIdle()
            compose.onNodeWithTag("navigation_BODY").assertIsSelected()
            compose.onNodeWithText("3 months").performSemanticsAction(SemanticsActions.OnClick) {
                it()
            }
            clickTab("SETTINGS")
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { input.start() }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnUiThread { input.progress(.55f) }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onNodeWithText("Your body, over time").assertExists()
            compose.onNodeWithTag("navigation_SETTINGS").assertIsSelected()
            capture("predictive-back-preview")
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { input.cancel() }
            compose.waitForIdle()
            compose.onNodeWithTag("navigation_SETTINGS").assertIsSelected()
            compose.onNodeWithTag("settings_title").assertIsDisplayed()
            compose.runOnUiThread {
                input.start()
                input.progress(.8f)
                input.complete()
            }
            compose.waitForIdle()
            compose.onNodeWithTag("navigation_BODY").assertIsSelected()
            compose.onNodeWithText("3 months").assertIsSelected()
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { compose.activity.navigationEventDispatcher.removeInput(input) }
        }
    }

    @Test
    fun privacyUsesTheSameInAppBackStack() {
        val input = TestBackInput()
        compose.runOnUiThread { compose.activity.navigationEventDispatcher.addInput(input) }
        try {
            clickTab("SETTINGS")
            compose
                .onNodeWithTag("settings_list")
                .performScrollToNode(hasText("Privacy & permissions"))
            compose.onNodeWithText("Native Android", substring = true).assertDoesNotExist()
            capture("settings-scrolled")
            compose.onNodeWithText("Privacy & permissions").performSemanticsAction(
                SemanticsActions.OnClick,
            ) {
                it()
            }
            compose.onNodeWithText("Your health, on your phone").assertIsDisplayed()
            compose.onNodeWithTag("navigation_SETTINGS").assertIsSelected()
            compose.runOnUiThread { input.complete() }
            compose.waitForIdle()
            compose.onNodeWithText("Privacy & permissions").assertIsDisplayed()
            compose.onNodeWithText("Your health, on your phone").assertDoesNotExist()
        } finally {
            compose.runOnUiThread { compose.activity.navigationEventDispatcher.removeInput(input) }
        }
    }

    @Test
    fun navigationRestoresTheSelectedPageAfterActivityRecreation() {
        clickTab("BODY")
        clickTab("SETTINGS")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("navigation_SETTINGS").assertIsSelected()
        compose.onNodeWithTag("settings_title").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("navigation_BODY").assertIsSelected()
    }

    private fun capture(name: String) {
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(compose.activity.cacheDir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun clickTab(name: String) =
        compose.onNodeWithTag("navigation_$name").performSemanticsAction(SemanticsActions.OnClick) {
            it()
        }

    private class TestBackInput : NavigationEventInput() {
        fun start() = dispatchOnBackStarted(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT))

        fun progress(amount: Float) =
            dispatchOnBackProgressed(
                NavigationEvent(
                    swipeEdge = NavigationEvent.EDGE_LEFT,
                    progress = amount,
                    touchX = 300f,
                    touchY = 800f,
                ),
            )

        fun cancel() = dispatchOnBackCancelled()

        fun complete() = dispatchOnBackCompleted()
    }
}
