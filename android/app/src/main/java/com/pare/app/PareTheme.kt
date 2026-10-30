package com.pare.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val LightPalette = lightColorScheme(
    primary = Color(0xFF285E49), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EEE6), onPrimaryContainer = Color(0xFF194432),
    secondary = Color(0xFF806019), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6ECD4), onSecondaryContainer = Color(0xFF594315),
    tertiary = Color(0xFF476F8B), onTertiary = Color.White,
    background = Color(0xFFF6F7F2), onBackground = Color(0xFF21352A),
    surface = Color.White, onSurface = Color(0xFF21352A),
    surfaceVariant = Color(0xFFF0F2ED), onSurfaceVariant = Color(0xFF5A685E),
    outline = Color(0xFF79877E), outlineVariant = Color(0xFFDDE4DC),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F6F1),
    surfaceContainer = Color(0xFFEFF2EC), surfaceContainerHigh = Color(0xFFE9EEE7),
    surfaceContainerHighest = Color(0xFFE2E9DF),
    error = Color(0xFFAB3D32), onError = Color.White,
    errorContainer = Color(0xFFFCE9E6), onErrorContainer = Color(0xFF752019)
)
internal val DarkPalette = darkColorScheme(
    primary = Color(0xFFA4D3B5), onPrimary = Color(0xFF123B27),
    primaryContainer = Color(0xFF263F31), onPrimaryContainer = Color(0xFFC4E7D0),
    secondary = Color(0xFFE0C27D), onSecondary = Color(0xFF3E3013),
    secondaryContainer = Color(0xFF353020), onSecondaryContainer = Color(0xFFF3DCA4),
    tertiary = Color(0xFF9FBED8), onTertiary = Color(0xFF1D3448),
    background = Color(0xFF121B16), onBackground = Color(0xFFE7EEE8),
    surface = Color(0xFF1B261F), onSurface = Color(0xFFE7EEE8),
    surfaceVariant = Color(0xFF28342C), onSurfaceVariant = Color(0xFFAFBDB2),
    outline = Color(0xFF85978B), outlineVariant = Color(0xFF36443A),
    surfaceContainerLowest = Color(0xFF0F1712), surfaceContainerLow = Color(0xFF17221B),
    surfaceContainer = Color(0xFF1B261F), surfaceContainerHigh = Color(0xFF28342C),
    surfaceContainerHighest = Color(0xFF304036),
    error = Color(0xFFF0ADA4), onError = Color(0xFF531D18),
    errorContainer = Color(0xFF432825), onErrorContainer = Color(0xFFFFD9D4)
)

private val PareTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp)
)

private val PareShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/** Priority color is supplementary: every use also retains a readable priority label. */
@Composable internal fun pareQuadrantColor(index: Int): Color {
    val dark = MaterialTheme.colorScheme.background == DarkPalette.background
    return when (index) {
        0 -> if (dark) Color(0xFFD99783) else Color(0xFFA04E3B)
        1 -> MaterialTheme.colorScheme.primary
        2 -> if (dark) Color(0xFF9FBBD0) else Color(0xFF466D88)
        else -> if (dark) Color(0xFF9AB39E) else Color(0xFF536F5C)
    }
}

@Composable fun PareTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkPalette else LightPalette,
        typography = PareTypography,
        shapes = PareShapes,
        content = content
    )
}
