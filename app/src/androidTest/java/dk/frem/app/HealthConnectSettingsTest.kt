package dk.frem.app

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dk.frem.app.data.Preferences
import dk.frem.app.health.HealthCapabilities
import dk.frem.app.health.HealthDataType
import dk.frem.app.health.HealthPermissionStatus
import dk.frem.app.ui.FremTheme
import dk.frem.app.ui.HealthConnectionSheet
import dk.frem.app.ui.healthConnectSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Permission fixtures stay in composition; no health records or device permissions are changed. */
class HealthConnectSettingsTest {
    @get:Rule val compose = createComposeRule()

    private val capabilities =
        HealthCapabilities(
            available = true,
            groups =
                linkedMapOf(
                    "Body measurements" to
                        listOf(
                            HealthDataType("Weight", "read_weight", "write_weight"),
                            HealthDataType("Height", "read_height", "write_height"),
                        ),
                    "Sleep" to listOf(HealthDataType("Sleep", "read_sleep")),
                ),
            backgroundPermission = setOf("background"),
            historyPermission = setOf("history"),
            loaded = true,
        )

    @Test
    fun connectionTracksDataPermissionsIncludingWriteOnlyAndRevokedAccess() {
        val granted = mutableStateOf(setOf("background", "history"))
        showSettings(granted)
        compose.onNodeWithText("Not connected").assertIsDisplayed()
        compose.onNodeWithText("Connect health data").assertIsDisplayed()
        compose.onNodeWithText("Data access").assertDoesNotExist()

        compose.runOnIdle { granted.value = setOf("read_weight") }
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("Connect health data").assertDoesNotExist()
        compose.onNodeWithText("Change access").assertIsDisplayed()

        compose.runOnIdle { granted.value = setOf("write_weight") }
        compose.onNodeWithText("Connected").assertIsDisplayed()
        scrollTo("Share: Weight")
        compose.onAllNodesWithText("Read: Off").onFirst().assertIsDisplayed()

        compose.runOnIdle { granted.value = emptySet() }
        scrollTo("Not connected")
        compose.onNodeWithText("Connect health data").assertIsDisplayed()
        compose.onNodeWithText("Data access").assertDoesNotExist()
    }

    @Test
    fun partialAccessNamesExactlyWhatIsReadAndSharedAndRequestsEachDirectionSeparately() {
        var requested: Set<String>? = null
        var settingsOpened = 0
        showSettings(
            mutableStateOf(setOf("read_weight", "write_height")),
            onRequest = { requested = it },
            onHealthSettings = { settingsOpened++ },
        )
        scrollTo("Read: Weight")
        compose.onNodeWithText("Not read: Height").assertExists()
        compose.onNodeWithText("Share: Height").assertExists()
        compose.onNodeWithText("Not shared: Weight").assertExists()
        compose.onNodeWithText("Body measurements").performClick()

        scrollToDescription("Read Height")
        compose.onNodeWithContentDescription("Read Height").performClick()
        assertEquals(setOf("read_height"), requested)
        compose.onNodeWithContentDescription("Share Weight").performClick()
        assertEquals(setOf("write_weight"), requested)
        compose.onNodeWithContentDescription("Read Weight").performClick()
        assertEquals(1, settingsOpened)
        compose.onNodeWithContentDescription("Share Height").performClick()
        assertEquals(2, settingsOpened)

        scrollTo("Share: Never shared by Frem")
        compose.onNodeWithText("Share: Never shared by Frem").assertIsDisplayed()
    }

    @Test
    fun backgroundSwitchReflectsRevocationAndReusesAnExistingGrant() {
        val granted = mutableStateOf(setOf("read_weight"))
        val preferences = mutableStateOf(Preferences(backgroundSync = true))
        var requested: Set<String>? = null
        var enabled: Boolean? = null
        showSettings(
            granted,
            preferences = preferences,
            onRequest = { requested = it },
            onBackgroundChange = { enabled = it },
        )
        scrollTo("Background updates")
        val toggle = compose.onNodeWithContentDescription("Background updates")
        toggle.assertIsOff().performClick()
        assertEquals(setOf("background"), requested)
        assertNull(enabled)

        compose.runOnIdle { granted.value = granted.value + "background" }
        toggle.assertIsOn().performClick()
        assertEquals(false, enabled)

        compose.runOnIdle { preferences.value = Preferences(backgroundSync = false) }
        requested = null
        toggle.assertIsOff().performClick()
        assertEquals(true, enabled)
        assertNull(requested)
    }

    @Test
    fun historyStatusChangesWhenAccessIsGrantedAndOpensSettingsToChangeIt() {
        val granted = mutableStateOf(setOf("read_weight"))
        var requested: Set<String>? = null
        var settingsOpened = false
        showSettings(
            granted,
            onRequest = { requested = it },
            onHealthSettings = { settingsOpened = true },
        )
        scrollTo("Older history")
        compose.onNodeWithText("Older history").performClick()
        assertEquals(setOf("history"), requested)
        compose.runOnIdle { granted.value = granted.value + "history" }
        compose.onNodeWithText("On · Frem can import up to a year of history").assertExists()
        compose.onNodeWithText("Older history").performClick()
        assertEquals(true, settingsOpened)
    }

    @Test
    fun unsupportedOptionalFeaturesAreDisabled() {
        showSettings(
            mutableStateOf(setOf("read_weight")),
            capabilities =
                capabilities.copy(
                    backgroundPermission = emptySet(),
                    historyPermission = emptySet(),
                ),
        )
        scrollTo("Background updates")
        compose
            .onNodeWithContentDescription("Background updates")
            .assertIsOff()
            .assertIsNotEnabled()
        scrollTo("Older history")
        compose.onNodeWithText("Older history").assertIsNotEnabled()
    }

    @Test
    fun checkingAndFailedPermissionQueriesDoNotClaimTheAppIsDisconnected() {
        val status = mutableStateOf(HealthPermissionStatus.CHECKING)
        showSettings(mutableStateOf(emptySet()), status = status)
        compose.onNodeWithText("Checking access…").assertIsDisplayed()
        compose.onNodeWithText("Connect health data").assertDoesNotExist()
        compose.runOnIdle { status.value = HealthPermissionStatus.ERROR }
        compose.onNodeWithText("Couldn’t check access").assertIsDisplayed()
        compose.onNodeWithText("Not connected").assertDoesNotExist()
        compose.onNodeWithText("Open Health Connect").assertIsDisplayed()
    }

    @Test
    fun unavailableHealthConnectShowsSetupInsteadOfConnectionOrPermissionControls() {
        showSettings(
            mutableStateOf(setOf("read_weight")),
            capabilities = capabilities.copy(available = false),
        )
        compose.onNodeWithText("Unavailable on this phone").assertIsDisplayed()
        compose.onNodeWithText("Set up Health Connect").assertIsDisplayed()
        compose.onNodeWithText("Connect health data").assertDoesNotExist()
        compose.onNodeWithText("Data access").assertDoesNotExist()
    }

    @Test
    fun connectionChoicesDoNotRequestSharingByDefault() {
        var requested: Set<String>? = null
        showConnectionChoices { requested = it }
        continueConnecting()
        assertEquals(setOf("read_weight", "read_height", "read_sleep"), requested)
    }

    @Test
    fun connectionChoicesAllowSharingToBeSelectedIndependently() {
        var requested: Set<String>? = null
        showConnectionChoices { requested = it }
        compose
            .onNodeWithTag("health_connection_choices")
            .performScrollToNode(hasTestTag("health_share_Weight"))
        compose.onNodeWithTag("health_share_Weight").assertIsOff().performClick()
        continueConnecting()
        assertEquals(setOf("read_weight", "read_height", "read_sleep", "write_weight"), requested)
    }

    private fun showSettings(
        granted: State<Set<String>>,
        preferences: State<Preferences> = mutableStateOf(Preferences()),
        capabilities: HealthCapabilities = this.capabilities,
        status: State<HealthPermissionStatus> = mutableStateOf(HealthPermissionStatus.READY),
        onRequest: (Set<String>) -> Unit = {},
        onBackgroundChange: (Boolean) -> Unit = {},
        onHealthSettings: () -> Unit = {},
    ) {
        compose.setContent {
            FremTheme {
                Surface {
                    LazyColumn(Modifier.testTag("health_settings_test")) {
                        healthConnectSettings(
                            preferences.value,
                            granted.value,
                            capabilities,
                            status.value,
                            onBackgroundChange,
                            onConnect = {},
                            onRequest,
                            onHealthSettings,
                        )
                    }
                }
            }
        }
    }

    private fun showConnectionChoices(onRequest: (Set<String>) -> Unit) {
        compose.setContent {
            FremTheme {
                HealthConnectionSheet(capabilities, emptySet(), {}, onRequest, {}, {})
            }
        }
    }

    private fun continueConnecting() {
        compose
            .onNodeWithTag("health_connection_choices")
            .performScrollToNode(hasText("Continue to Health Connect"))
        compose.onNodeWithText("Continue to Health Connect").performClick()
    }

    private fun scrollTo(text: String) {
        compose.onNodeWithTag("health_settings_test").performScrollToNode(hasText(text))
    }

    private fun scrollToDescription(description: String) {
        compose
            .onNodeWithTag("health_settings_test")
            .performScrollToNode(hasContentDescription(description))
    }
}
