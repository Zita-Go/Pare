package com.pare.app

import androidx.compose.foundation.shape.RoundedCornerShape
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
    primary = Color(0xFF795A21), onPrimary = Color.White,
    primaryContainer = Color(0xFFF0E3BC), onPrimaryContainer = Color(0xFF423719),
    secondary = Color(0xFF6F5D34), onSecondary = Color.White,
    secondaryContainer = Color(0xFFECE7D4), onSecondaryContainer = Color(0xFF494126),
    tertiary = Color(0xFF48634F), onTertiary = Color.White,
    background = Color(0xFFF4F3EB), onBackground = Color(0xFF24372B),
    surface = Color(0xFFFCFBF5), onSurface = Color(0xFF24372B),
    surfaceVariant = Color(0xFFE9ECE2), onSurfaceVariant = Color(0xFF5B6C5F),
    outline = Color(0xFF7E8D80), outlineVariant = Color(0xFFD8DFD2),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF8F8F1),
    surfaceContainer = Color(0xFFEEF0E7), surfaceContainerHigh = Color(0xFFE6EBDD),
    surfaceContainerHighest = Color(0xFFDFE6D8)
)
private val DarkPalette = darkColorScheme(
    primary = Color(0xFFE8C87A), onPrimary = Color(0xFF262319),
    primaryContainer = Color(0xFF48422C), onPrimaryContainer = Color(0xFFF1DDA7),
    secondary = Color(0xFFD8BE83), onSecondary = Color(0xFF302A1B),
    secondaryContainer = Color(0xFF373A28), onSecondaryContainer = Color(0xFFE7D7A7),
    tertiary = Color(0xFFA2BBA6), onTertiary = Color(0xFF203328),
    background = Color(0xFF14211A), onBackground = Color(0xFFF2F1E7),
    surface = Color(0xFF203127), onSurface = Color(0xFFF2F1E7),
    surfaceVariant = Color(0xFF283B30), onSurfaceVariant = Color(0xFFAFBFB2),
    outline = Color(0xFF758E7C), outlineVariant = Color(0xFF35483C),
    surfaceContainerLowest = Color(0xFF111D16), surfaceContainerLow = Color(0xFF1A2A21),
    surfaceContainer = Color(0xFF203127), surfaceContainerHigh = Color(0xFF283B30),
    surfaceContainerHighest = Color(0xFF304538),
    error = Color(0xFFF0AC9C), onError = Color(0xFF55281F),
    errorContainer = Color(0xFF472F29), onErrorContainer = Color(0xFFFFDAD1),
    inverseSurface = Color(0xFFE5EADF), inverseOnSurface = Color(0xFF24372B),
    inversePrimary = Color(0xFF795A21), surfaceTint = Color(0xFFE8C87A)
)

private val PareTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp)
)

private val PareShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(20.dp),
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

@Composable fun PareTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkPalette else LightPalette,
        typography = PareTypography,
        shapes = PareShapes,
        content = content
    )
}
