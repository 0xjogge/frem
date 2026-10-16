package dk.frem.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class LiveSettingsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun savedSettingsAndThemeControlsUpdateWithoutLeavingSettings() {
        val repository = (compose.activity.application as FremApplication).repository
        val original = runBlocking { repository.preferences.flow.first() }
        try {
            compose.onNodeWithTag("navigation_SETTINGS").performSemanticsAction(
                SemanticsActions.OnClick,
            ) {
                it()
            }
            val toggle = compose.onNodeWithContentDescription("Imperial units")
            toggle.performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5000) {
                toggle.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] ==
                    if (original.imperial) ToggleableState.Off else ToggleableState.On
            }
            compose.onNodeWithTag("navigation_SETTINGS").assertIsSelected()
            toggle.performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5000) {
                toggle.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] ==
                    if (original.imperial) ToggleableState.On else ToggleableState.Off
            }
            for (theme in listOf("Light", "Dark")) {
                compose.onNodeWithTag("settings_list").performScrollToNode(hasText(theme))
                compose.onNodeWithText(theme).performSemanticsAction(SemanticsActions.OnClick) {
                    it()
                }
                compose.waitUntil(5000) {
                    compose
                        .onNodeWithText(theme)
                        .fetchSemanticsNode()
                        .config[SemanticsProperties.Selected]
                }
                compose.onNodeWithTag("navigation_SETTINGS").assertIsSelected()
            }
        } finally {
            runBlocking {
                repository.preferences.flag("imperial", original.imperial)
                repository.preferences.text("theme", original.theme)
            }
        }
    }

    @Test
    fun captureLightAndDarkCardsUsingTheUsersExistingData() {
        val repository = (compose.activity.application as FremApplication).repository
        val original = runBlocking { repository.preferences.flow.first() }
        try {
            runBlocking { repository.preferences.flag("dynamic", false) }
            for (theme in listOf("light", "dark")) {
                runBlocking { repository.preferences.text("theme", theme) }
                compose.onNodeWithTag("navigation_SETTINGS").performSemanticsAction(
                    SemanticsActions.OnClick,
                ) {
                    it()
                }
                compose
                    .onNodeWithTag("settings_list")
                    .performScrollToNode(hasText(theme.replaceFirstChar(Char::uppercase)))
                compose.waitUntil(5000) {
                    compose
                        .onNodeWithText(theme.replaceFirstChar(Char::uppercase))
                        .fetchSemanticsNode()
                        .config[SemanticsProperties.Selected]
                }
                for (page in listOf("TODAY", "BODY", "HEALTH")) {
                    compose.onNodeWithTag("navigation_$page").performSemanticsAction(
                        SemanticsActions.OnClick,
                    ) {
                        it()
                    }
                    compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                        File(compose.activity.cacheDir, "contrast-$theme-$page.png")
                            .outputStream()
                            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            }
        } finally {
            runBlocking {
                repository.preferences.flag("dynamic", original.dynamicColor)
                repository.preferences.text("theme", original.theme)
            }
        }
    }
}
