package com.step.desktop

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val StepGreen = Color(0xFF234E40)
internal val StepGold = Color(0xFFA57A25)
internal val StepMuted = Color(0xFF69716C)
internal val StepLine = Color(0xFFE2E5DE)

@Composable
fun DesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = StepGreen, onPrimary = Color.White,
            primaryContainer = Color(0xFFE2EBE3), onPrimaryContainer = StepGreen,
            secondary = StepGold, secondaryContainer = Color(0xFFF3E9CD),
            background = Color(0xFFF9FAF6), surface = Color(0xFFFFFEFA),
            surfaceVariant = Color(0xFFF0F2EC), onSurface = Color(0xFF26352E),
            onSurfaceVariant = StepMuted, outline = Color(0xFF9CA69D), outlineVariant = StepLine,
            error = Color(0xFFA33D36)
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontSize=30.sp, fontWeight=FontWeight.SemiBold, lineHeight=40.sp),
            headlineMedium = TextStyle(fontSize=24.sp, fontWeight=FontWeight.SemiBold, lineHeight=32.sp),
            titleLarge = TextStyle(fontSize=19.sp, fontWeight=FontWeight.SemiBold, lineHeight=28.sp),
            titleMedium = TextStyle(fontSize=15.sp, fontWeight=FontWeight.SemiBold, lineHeight=23.sp),
            bodyLarge = TextStyle(fontFamily=FontFamily.SansSerif, fontSize=15.sp, lineHeight=24.sp),
            bodyMedium = TextStyle(fontSize=14.sp, lineHeight=22.sp),
            bodySmall = TextStyle(fontSize=12.sp, lineHeight=19.sp),
            labelLarge = TextStyle(fontSize=13.sp, fontWeight=FontWeight.Medium, lineHeight=20.sp)
        ),
        shapes = Shapes(small=RoundedCornerShape(7.dp), medium=RoundedCornerShape(12.dp), large=RoundedCornerShape(16.dp)),
        content = content
    )
}
