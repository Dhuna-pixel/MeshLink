package com.meshlink.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = MeshCyanPrimary,
    onPrimary = MeshDarkBackground,
    primaryContainer = MeshCyanContainer,
    onPrimaryContainer = TextPrimary,
    secondary = MeshGreenAccent,
    onSecondary = MeshDarkBackground,
    secondaryContainer = MeshGreenContainer,
    onSecondaryContainer = TextPrimary,
    tertiary = MeshPurpleAccent,
    background = MeshDarkBackground,
    onBackground = TextPrimary,
    surface = MeshDarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = MeshDarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = MeshRedError,
    onError = TextPrimary
)

private val LightColorScheme = DarkColorScheme // Defaulting to high-contrast engineering dark palette

@Composable
fun MeshLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
