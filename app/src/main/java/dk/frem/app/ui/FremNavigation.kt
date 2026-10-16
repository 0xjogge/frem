package dk.frem.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay

enum class FremDestination(val label: String) {
    TODAY("Today"),
    ACTIVITY("Activity"),
    BODY("Body"),
    HEALTH("Health"),
    SETTINGS("Settings"),
    PRIVACY("Privacy & permissions");

    val topLevel: Boolean
        get() = this != PRIVACY
}

class FremNavigation(private val destinations: SnapshotStateList<FremDestination>) {
    val backStack: List<FremDestination>
        get() = destinations

    val current: FremDestination
        get() = destinations.last()

    fun navigate(destination: FremDestination) {
        if (destination == current) return
        val existing = destinations.indexOf(destination)
        if (existing >= 0) {
            while (destinations.lastIndex > existing) destinations.removeAt(destinations.lastIndex)
        } else {
            destinations.add(destination)
        }
    }

    fun goBack() {
        if (destinations.size > 1) destinations.removeAt(destinations.lastIndex)
    }

    companion object {
        val Saver =
            listSaver<FremNavigation, String>(
                save = { it.backStack.map(FremDestination::name) },
                restore = { names ->
                    FremNavigation(names.map(FremDestination::valueOf).toMutableStateList())
                },
            )
    }
}

@Composable
fun rememberFremNavigation(): FremNavigation =
    rememberSaveable(saver = FremNavigation.Saver) {
        FremNavigation(mutableStateListOf(FremDestination.TODAY))
    }

@Composable
fun FremNavDisplay(
    navigation: FremNavigation,
    modifier: Modifier = Modifier,
    content: @Composable (FremDestination) -> Unit,
) {
    // Navigation retains entries by key. Read current content inside each entry
    // so retained pages observe fresh data, preferences, and event callbacks.
    val currentContent = rememberUpdatedState(content)
    NavDisplay(
        backStack = navigation.backStack,
        onBack = navigation::goBack,
        modifier = modifier,
        transitionSpec = {
            (scaleIn(initialScale = .92f, animationSpec = tween(220)) +
                slideInVertically(initialOffsetY = { it / 20 }, animationSpec = tween(220)) +
                fadeIn(tween(120))) togetherWith fadeOut(tween(90))
        },
        popTransitionSpec = {
            fadeIn(tween(90)) togetherWith
                (scaleOut(targetScale = .92f, animationSpec = tween(180)) +
                    slideOutVertically(targetOffsetY = { it / 20 }, animationSpec = tween(180)) +
                    fadeOut(tween(120)))
        },
        entryProvider = { destination ->
            NavEntry(destination) { currentContent.value(destination) }
        },
    )
}
