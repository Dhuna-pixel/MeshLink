package com.meshlink.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

internal val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF82D8CF), onPrimary = Color(0xFF003732),
    primaryContainer = Color(0xFF193F3C), onPrimaryContainer = Color(0xFFBEF1EA),
    secondary = Color(0xFF91D5AD), onSecondary = Color(0xFF103825),
    secondaryContainer = Color(0xFF254535), onSecondaryContainer = Color(0xFFBCEED0),
    tertiary = Color(0xFFC8B8E8), onTertiary = Color(0xFF34264D),
    tertiaryContainer = Color(0xFF3F354F), onTertiaryContainer = Color(0xFFEBDDFB),
    background = Color(0xFF101414), onBackground = Color(0xFFF0F1EB),
    surface = Color(0xFF191E1E), onSurface = Color(0xFFF0F1EB),
    outlineVariant = Color(0xFF35423E), surfaceTint = Color(0xFF82D8CF),
    surfaceVariant = Color(0xFF252D2D), onSurfaceVariant = Color(0xFFB8C5C2),
    outline = Color(0xFF82938F), error = Color(0xFFFFB4A8), onError = Color(0xFF690005)
)
internal val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006B62), onPrimary = Color.White,
    primaryContainer = Color(0xFFB9EEE4), onPrimaryContainer = Color(0xFF00201D),
    secondary = Color(0xFF286847), onSecondary = Color.White,
    secondaryContainer = Color(0xFFBAEED0), onSecondaryContainer = Color(0xFF002110),
    tertiary = Color(0xFF695581), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFECDFF7), onTertiaryContainer = Color(0xFF28163D),
    background = Color(0xFFF6F7F2), onBackground = Color(0xFF192320),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF192320),
    outlineVariant = Color(0xFFCDD8D1), surfaceTint = Color(0xFF006B62),
    surfaceVariant = Color(0xFFE6ECE6), onSurfaceVariant = Color(0xFF44544F),
    outline = Color(0xFF74847E), error = Color(0xFFB32625), onError = Color.White
)

@Composable
fun MeshLinkTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window,view).apply {
                isAppearanceLightStatusBars = !darkTheme
                // Dark navigation icons are supported starting at API 26.
                isAppearanceLightNavigationBars = !darkTheme
            }
            if (Build.VERSION.SDK_INT < 26 && !darkTheme) window.navigationBarColor = DarkColorScheme.background.toArgb()
        }
    }
    MaterialTheme(colorScheme = colors, typography = Typography, shapes = MeshShapes, content = content)
}
