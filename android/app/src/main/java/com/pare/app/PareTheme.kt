package com.pare.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightPalette = lightColorScheme(
    primary = Color(0xFF244D40), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDEBE0), onPrimaryContainer = Color(0xFF18372B),
    secondary = Color(0xFF795C29), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E5CB), onSecondaryContainer = Color(0xFF503B16),
    background = Color(0xFFF7F5EF), onBackground = Color(0xFF242D27),
    surface = Color(0xFFFDFBF6), onSurface = Color(0xFF242D27),
    surfaceVariant = Color(0xFFECEEE5), onSurfaceVariant = Color(0xFF576158),
    outline = Color(0xFF7B857A), outlineVariant = Color(0xFFD6DCD0),
    surfaceContainer = Color(0xFFF0EFE7), surfaceContainerLow = Color(0xFFF9F7F1),
    surfaceContainerHigh = Color(0xFFEAECE2)
)
private val DarkPalette = darkColorScheme(
    primary = Color(0xFFA6D0B7), onPrimary = Color(0xFF103729),
    primaryContainer = Color(0xFF2D5140), onPrimaryContainer = Color(0xFFC1EAD1),
    secondary = Color(0xFFE1C78E), onSecondary = Color(0xFF40300F),
    secondaryContainer = Color(0xFF57451F), onSecondaryContainer = Color(0xFFF3DFAF),
    background = Color(0xFF151C18), onBackground = Color(0xFFE2E7DF),
    surface = Color(0xFF1C241E), onSurface = Color(0xFFE2E7DF),
    surfaceVariant = Color(0xFF344036), onSurfaceVariant = Color(0xFFBAC7B9),
    outline = Color(0xFF8C9B8C), outlineVariant = Color(0xFF414E42),
    surfaceContainer = Color(0xFF202A22), surfaceContainerLow = Color(0xFF1A221C),
    surfaceContainerHigh = Color(0xFF2A352D)
)

@Composable fun PareTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkPalette else LightPalette, content = content)
}
