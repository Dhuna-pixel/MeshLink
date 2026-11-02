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
    primary = Color(0xFF00E5FF), onPrimary = Color(0xFF002F37),
    primaryContainer = Color(0xFF004D61), onPrimaryContainer = Color(0xFFC1F4FF),
    secondary = Color(0xFF66E59B), onSecondary = Color(0xFF00391C),
    secondaryContainer = Color(0xFF004D25), onSecondaryContainer = Color(0xFFB0F7C7),
    tertiary = Color(0xFFCEB7FF), onTertiary = Color(0xFF32105F),
    background = Color(0xFF0F172A), onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF1E293B), onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF334155), onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF94A3B8), error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
)
internal val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006779), onPrimary = Color.White,
    primaryContainer = Color(0xFFB9EEFA), onPrimaryContainer = Color(0xFF001F26),
    secondary = Color(0xFF006D3B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFB6F2CE), onSecondaryContainer = Color(0xFF002110),
    tertiary = Color(0xFF67409B), onTertiary = Color.White,
    background = Color(0xFFF6F8FC), onBackground = Color(0xFF172234),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF172234),
    surfaceVariant = Color(0xFFE3EAF1), onSurfaceVariant = Color(0xFF3E5062),
    outline = Color(0xFF64748B), error = Color(0xFFBA1A1A), onError = Color.White
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
    MaterialTheme(colorScheme = colors, typography = Typography, content = content)
}
