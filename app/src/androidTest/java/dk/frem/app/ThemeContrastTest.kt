package dk.frem.app

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.test.core.app.ApplicationProvider
import dk.frem.app.ui.fremColorScheme
import dk.frem.app.ui.healthAccent
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test
    fun textAndDataAccentsHaveReadableContrastInStaticAndDynamicThemes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val themes =
            mutableListOf("light" to fremColorScheme(false), "dark" to fremColorScheme(true))
        if (Build.VERSION.SDK_INT >= 31) {
            themes += "dynamic light" to dynamicLightColorScheme(context)
            themes += "dynamic dark" to dynamicDarkColorScheme(context)
        }
        for ((name, colors) in themes) {
            check(name, "primary text", colors.onPrimary, colors.primary)
            check(
                name,
                "step card text and goal caption",
                colors.onPrimaryContainer,
                colors.primaryContainer,
            )
            check(name, "selected controls", colors.onSecondaryContainer, colors.secondaryContainer)
            check(name, "weight card text", colors.onTertiaryContainer, colors.tertiaryContainer)
            check(name, "dialogs", colors.onSurface, colors.surfaceContainerHigh)
            check(name, "error text", colors.error, colors.surface)
            check(name, "snackbar", colors.inverseOnSurface, colors.inverseSurface)
            for (background in surfaces(colors)) {
                check(name, "secondary text", colors.onSurfaceVariant, background)
            }
            check(
                name,
                "hydration and sleep icons",
                colors.healthAccent,
                colors.surfaceContainerLow,
                3.0,
            )
            check(name, "chart bars", colors.primary, colors.surfaceContainerLow, 3.0)
            check(name, "weight trend", colors.tertiary, colors.surfaceContainerLow, 3.0)
        }
    }

    private fun surfaces(colors: ColorScheme) =
        listOf(
            colors.background,
            colors.surfaceContainerLow,
            colors.surfaceContainer,
            colors.surfaceContainerHigh,
            colors.surfaceContainerHighest,
        )

    private fun check(
        theme: String,
        label: String,
        foreground: Color,
        background: Color,
        minimum: Double = 4.5,
    ) {
        val light = maxOf(foreground.luminance(), background.luminance())
        val dark = minOf(foreground.luminance(), background.luminance())
        val ratio = (light + .05) / (dark + .05)
        assertTrue("$theme $label: $ratio is below $minimum", ratio >= minimum)
    }
}
