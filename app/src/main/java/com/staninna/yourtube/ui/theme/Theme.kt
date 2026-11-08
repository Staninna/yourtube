package com.staninna.yourtube.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Whether this device can theme the app from the wallpaper (Material You, Android 12 and later). */
val dynamicColorAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * The app theme: Material 3 Expressive (expressive motion, shapes and type) with wallpaper colors
 * when [dynamicColor] is on and the device has them, and a warm red palette otherwise.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TubeTheme(dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors: ColorScheme = when {
        dynamicColor && dynamicColorAvailable && dark -> dynamicDarkColorScheme(context)
        dynamicColor && dynamicColorAvailable -> dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        shapes = TubeShapes,
        typography = TubeTypography,
        content = content,
    )
}

// Tonal palette from a YouTube-adjacent red seed (#B3261E), light and dark, with every surface role set
// so containers, sheets and the navigation bar step in tone instead of falling back to defaults.
private val FallbackLight = lightColorScheme(
    primary = Color(0xFFB3261E), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD5), onPrimaryContainer = Color(0xFF410001),
    secondary = Color(0xFF775651), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD5), onSecondaryContainer = Color(0xFF2C1512),
    tertiary = Color(0xFF705C2E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFCDFA6), onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFFFF8F6), onBackground = Color(0xFF231918),
    surface = Color(0xFFFFF8F6), onSurface = Color(0xFF231918),
    surfaceVariant = Color(0xFFF5DDDA), onSurfaceVariant = Color(0xFF534341),
    surfaceDim = Color(0xFFE8D6D3), surfaceBright = Color(0xFFFFF8F6),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFF0EE),
    surfaceContainer = Color(0xFFFCEAE7), surfaceContainerHigh = Color(0xFFF6E4E2),
    surfaceContainerHighest = Color(0xFFF1DEDC),
    outline = Color(0xFF857371), outlineVariant = Color(0xFFD8C2BF),
    inverseSurface = Color(0xFF392E2C), inverseOnSurface = Color(0xFFFFEDEA), inversePrimary = Color(0xFFFFB4A9),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFFFB4A9), onPrimary = Color(0xFF690002),
    primaryContainer = Color(0xFF8C1D18), onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFE7BDB7), onSecondary = Color(0xFF442925),
    secondaryContainer = Color(0xFF5D3F3B), onSecondaryContainer = Color(0xFFFFDAD5),
    tertiary = Color(0xFFDFC38C), onTertiary = Color(0xFF3E2E04),
    tertiaryContainer = Color(0xFF574419), onTertiaryContainer = Color(0xFFFCDFA6),
    background = Color(0xFF1A1110), onBackground = Color(0xFFF1DEDC),
    surface = Color(0xFF1A1110), onSurface = Color(0xFFF1DEDC),
    surfaceVariant = Color(0xFF534341), onSurfaceVariant = Color(0xFFD8C2BF),
    surfaceDim = Color(0xFF1A1110), surfaceBright = Color(0xFF423735),
    surfaceContainerLowest = Color(0xFF140C0B), surfaceContainerLow = Color(0xFF231918),
    surfaceContainer = Color(0xFF271D1C), surfaceContainerHigh = Color(0xFF322826),
    surfaceContainerHighest = Color(0xFF3D3231),
    outline = Color(0xFFA08C8A), outlineVariant = Color(0xFF534341),
    inverseSurface = Color(0xFFF1DEDC), inverseOnSurface = Color(0xFF392E2C), inversePrimary = Color(0xFFB3261E),
)

// The expressive shape scale: rounder than baseline M3 at every step.
private val TubeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// The baseline scale with heavier headings: screen titles (titleLargeEmphasized) and state titles
// (titleLarge) read as headings, list titles (titleSmall) stay calm.
private val TubeTypography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleLargeEmphasized = titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Medium),
    )
}
