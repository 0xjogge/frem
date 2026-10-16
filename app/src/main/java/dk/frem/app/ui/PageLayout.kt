package dk.frem.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/** Insets belong to the scrolling content, allowing pages to scroll behind system bars. */
@Composable
fun pageContentPadding(includeBottomInset: Boolean = false): PaddingValues {
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = 24.dp + insets.calculateStartPadding(direction),
        top = 24.dp + insets.calculateTopPadding(),
        end = 24.dp + insets.calculateEndPadding(direction),
        bottom = 24.dp + if (includeBottomInset) insets.calculateBottomPadding() else 0.dp,
    )
}
