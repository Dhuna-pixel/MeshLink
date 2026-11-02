package com.meshlink.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private fun contrast(a: Color,b: Color): Float {
        val first=a.luminance(); val second=b.luminance()
        return (maxOf(first,second)+0.05f)/(minOf(first,second)+0.05f)
    }
    @Test fun readableTextRolesInBothSchemes() {
        listOf(LightColorScheme,DarkColorScheme).forEach { colors ->
            listOf(colors.onBackground to colors.background,colors.onSurface to colors.surface,
                colors.onSurfaceVariant to colors.surfaceVariant,colors.onPrimary to colors.primary,
                colors.onSecondary to colors.secondary,colors.onTertiary to colors.tertiary,
                colors.primary to colors.surface,colors.secondary to colors.surface,colors.error to colors.surface).forEach { (text,background) ->
                assertTrue("Text contrast must be at least 4.5:1",contrast(text,background)>=4.5f)
            }
        }
    }
}
