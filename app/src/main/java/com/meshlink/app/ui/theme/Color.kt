package com.meshlink.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// Semantic aliases keep all existing screens, dialogs and badges on the active Material palette.
val MeshBackground: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
val MeshSurface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface
val MeshSurfaceVariant: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant
val MeshCyanPrimary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
val MeshCyanContainer: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primaryContainer
val MeshGreenAccent: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.secondary
val MeshGreenContainer: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.secondaryContainer
val MeshPurpleAccent: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.tertiary
val MeshAmberWarning: Color @Composable @ReadOnlyComposable get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFFFC46B) else Color(0xFF805500)
val MeshRedError: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error
val TextPrimary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
val TextSecondary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
val TextTertiary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
val CodeLogBackground: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
