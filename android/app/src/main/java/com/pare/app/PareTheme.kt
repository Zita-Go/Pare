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

private val LightPalette = lightColorScheme(
    primary = Color(0xFF246B4A), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F1E9), onPrimaryContainer = Color(0xFF174B32),
    secondary = Color(0xFF936510), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFF6DE), onSecondaryContainer = Color(0xFF624508),
    tertiary = Color(0xFF48779E), onTertiary = Color.White,
    background = Color.White, onBackground = Color(0xFF1D2522),
    surface = Color.White, onSurface = Color(0xFF1D2522),
    surfaceVariant = Color(0xFFF4F6F5), onSurfaceVariant = Color(0xFF626C66),
    outline = Color(0xFF89928D), outlineVariant = Color(0xFFE7E9EC),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F9F8),
    surfaceContainer = Color(0xFFF3F5F4), surfaceContainerHigh = Color(0xFFEDF0EE),
    surfaceContainerHighest = Color(0xFFE7EBE8),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFFCE9E6), onErrorContainer = Color(0xFF752019)
)
private val DarkPalette = darkColorScheme(
    primary = Color(0xFF99D2B3), onPrimary = Color(0xFF123925),
    primaryContainer = Color(0xFF244534), onPrimaryContainer = Color(0xFFBAE9CF),
    secondary = Color(0xFFE4C279), onSecondary = Color(0xFF3D2E0F),
    secondaryContainer = Color(0xFF2E2B20), onSecondaryContainer = Color(0xFFF1D99E),
    tertiary = Color(0xFF9FBED8), onTertiary = Color(0xFF1D3448),
    background = Color(0xFF151918), onBackground = Color(0xFFE6ECE8),
    surface = Color(0xFF1C2220), onSurface = Color(0xFFE6ECE8),
    surfaceVariant = Color(0xFF242B28), onSurfaceVariant = Color(0xFFA5B2AA),
    outline = Color(0xFF7A8980), outlineVariant = Color(0xFF303A34),
    surfaceContainerLowest = Color(0xFF111513), surfaceContainerLow = Color(0xFF191F1C),
    surfaceContainer = Color(0xFF1C2220), surfaceContainerHigh = Color(0xFF242B28),
    surfaceContainerHighest = Color(0xFF2B3430),
    error = Color(0xFFF0ADA4), onError = Color(0xFF531D18),
    errorContainer = Color(0xFF432825), onErrorContainer = Color(0xFFFFD9D4)
)

private val PareTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp)
)

private val PareShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/** Priority color is supplementary: every use also retains a readable priority label. */
@Composable internal fun pareQuadrantColor(index: Int): Color {
    val dark = MaterialTheme.colorScheme.background == DarkPalette.background
    return when (index) {
        0 -> if (dark) Color(0xFFD99783) else Color(0xFF9E4F3D)
        1 -> MaterialTheme.colorScheme.primary
        2 -> if (dark) Color(0xFF9FBBD0) else Color(0xFF416D8B)
        else -> if (dark) Color(0xFF9AB39E) else Color(0xFF526F57)
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
