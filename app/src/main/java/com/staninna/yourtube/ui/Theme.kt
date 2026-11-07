package com.staninna.yourtube.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// A YouTube-ish red seed for when Material You dynamic color isn't available.
private val FallbackDark = darkColorScheme(
    primary = Color(0xFFFF4D4D),
    secondary = Color(0xFFB0B8C4),
    background = Color(0xFF000000),
    surface = Color(0xFF0E0F12),
    surfaceContainer = Color(0xFF16181C),
    surfaceContainerHigh = Color(0xFF1E2126),
)

private val FallbackLight = lightColorScheme(primary = Color(0xFFD32F2F))

// Material 3 Expressive leans on generous, varied corner radii.
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun TubeTheme(dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val canDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colors = when {
        canDynamic && dark -> dynamicDarkColorScheme(context)
        canDynamic -> dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = colors, shapes = ExpressiveShapes, content = content)
}
