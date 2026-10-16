package dk.frem.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dk.frem.app.data.Preferences

val Forest = Color(0xFF234D39)
val Lime = Color(0xFFDCF0AB)
private val Light =
    lightColorScheme(
        primary = Forest,
        onPrimary = Color.White,
        primaryContainer = Lime,
        onPrimaryContainer = Forest,
        secondary = Color(0xFF536348),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE1E8D8),
        onSecondaryContainer = Color(0xFF273722),
        tertiary = Color(0xFF97573D),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFBE3D7),
        onTertiaryContainer = Color(0xFF543221),
        background = Color(0xFFF6F5EF),
        onBackground = Color(0xFF242A23),
        surface = Color(0xFFF6F5EF),
        onSurface = Color(0xFF242A23),
        surfaceDim = Color(0xFFDADFD3),
        surfaceBright = Color(0xFFF6F5EF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFFEF9),
        surfaceContainer = Color(0xFFECEDE5),
        surfaceContainerHigh = Color(0xFFE4E7DD),
        surfaceContainerHighest = Color(0xFFDBE0D3),
        surfaceVariant = Color(0xFFDEE4D5),
        onSurfaceVariant = Color(0xFF4B554A),
        outline = Color(0xFF6D7868),
        outlineVariant = Color(0xFFC4CDBD),
        surfaceTint = Forest,
        inverseSurface = Color(0xFF273128),
        inverseOnSurface = Color(0xFFF2F3EA),
        inversePrimary = Lime,
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        primaryFixed = Lime,
        primaryFixedDim = Color(0xFFBFD48F),
        onPrimaryFixed = Forest,
        onPrimaryFixedVariant = Color(0xFF35543D),
        secondaryFixed = Color(0xFFE1E8D8),
        secondaryFixedDim = Color(0xFFC3CFB7),
        onSecondaryFixed = Color(0xFF273722),
        onSecondaryFixedVariant = Color(0xFF3D4937),
        tertiaryFixed = Color(0xFFFBE3D7),
        tertiaryFixedDim = Color(0xFFE9AB8E),
        onTertiaryFixed = Color(0xFF543221),
        onTertiaryFixedVariant = Color(0xFF713F2A),
    )
private val Dark =
    darkColorScheme(
        primary = Lime,
        onPrimary = Forest,
        primaryContainer = Color(0xFF35543D),
        onPrimaryContainer = Lime,
        secondary = Color(0xFFC3CFB7),
        onSecondary = Color(0xFF263820),
        secondaryContainer = Color(0xFF3D4937),
        onSecondaryContainer = Color(0xFFE1E8D8),
        tertiary = Color(0xFFE9AB8E),
        onTertiary = Color(0xFF472817),
        tertiaryContainer = Color(0xFF553525),
        onTertiaryContainer = Color(0xFFFBE3D7),
        background = Color(0xFF151C17),
        onBackground = Color(0xFFE8EBDF),
        surface = Color(0xFF151C17),
        onSurface = Color(0xFFE8EBDF),
        surfaceDim = Color(0xFF151C17),
        surfaceBright = Color(0xFF3A463B),
        surfaceContainerLowest = Color(0xFF101611),
        surfaceContainerLow = Color(0xFF202923),
        surfaceContainer = Color(0xFF273128),
        surfaceContainerHigh = Color(0xFF303B31),
        surfaceContainerHighest = Color(0xFF374238),
        surfaceVariant = Color(0xFF3D493E),
        onSurfaceVariant = Color(0xFFB8C4B5),
        outline = Color(0xFF8D9A87),
        outlineVariant = Color(0xFF53604F),
        surfaceTint = Lime,
        inverseSurface = Color(0xFFE8EBDF),
        inverseOnSurface = Color(0xFF273128),
        inversePrimary = Forest,
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        primaryFixed = Lime,
        primaryFixedDim = Color(0xFFBFD48F),
        onPrimaryFixed = Forest,
        onPrimaryFixedVariant = Color(0xFF35543D),
        secondaryFixed = Color(0xFFE1E8D8),
        secondaryFixedDim = Color(0xFFC3CFB7),
        onSecondaryFixed = Color(0xFF273722),
        onSecondaryFixedVariant = Color(0xFF3D4937),
        tertiaryFixed = Color(0xFFFBE3D7),
        tertiaryFixedDim = Color(0xFFE9AB8E),
        onTertiaryFixed = Color(0xFF543221),
        onTertiaryFixedVariant = Color(0xFF713F2A),
    )

internal fun fremColorScheme(dark: Boolean): ColorScheme = if (dark) Dark else Light

/** Blue accents remain legible on the current theme's surfaces. */
val ColorScheme.healthAccent: Color
    get() = if (surface.luminance() < .5f) Color(0xFFB1C7FF) else Color(0xFF3455A0)

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun FremTheme(preferences: Preferences = Preferences(), content: @Composable () -> Unit) {
    val dark =
        when (preferences.theme) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }
    val colors =
        if (preferences.dynamicColor && Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(LocalContext.current)
            else dynamicLightColorScheme(LocalContext.current)
        } else fremColorScheme(dark)
    val view = LocalView.current
    if (!view.isInEditMode)
        SideEffect {
            view.context.activity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    MaterialExpressiveTheme(
        colorScheme = colors,
        shapes =
            Shapes(
                extraSmall = RoundedCornerShape(10.dp),
                small = RoundedCornerShape(16.dp),
                medium = RoundedCornerShape(24.dp),
                large = RoundedCornerShape(30.dp),
                extraLarge = RoundedCornerShape(36.dp),
            ),
        typography =
            Typography(
                displayLarge =
                    TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 62.sp,
                        letterSpacing = (-3).sp,
                    ),
                headlineLarge =
                    TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp,
                        letterSpacing = (-1).sp,
                    ),
                headlineMedium =
                    TextStyle(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 26.sp,
                        letterSpacing = (-0.6).sp,
                    ),
                titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
                titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
                bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
                labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
            ),
        content = content,
    )
}

private tailrec fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
