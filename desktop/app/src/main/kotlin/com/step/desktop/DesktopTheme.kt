package com.step.desktop

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow

object DesktopAppearance { val dark=MutableStateFlow(true) }

// Explicit variable-font axes avoid Windows resolving every requested weight as Thin.
private val StepFontFamily=FontFamily(listOf(400,500,600).map {weight->
    Font(resource="fonts/NotoSansSC-variable.ttf",weight=FontWeight(weight),
        variationSettings=FontVariation.Settings(FontVariation.weight(weight)))
})

@Immutable internal data class StepPalette(
    val sidebar:Color,val raised:Color,val field:Color,val selection:Color,val warm:Color,
    val success:Color,val info:Color,val quadrants:List<Color>
)
private val DarkPalette=StepPalette(
    Color(0xFF1D2822),Color(0xFF344038),Color(0xFF1D2821),Color(0xFF3C4232),Color(0xFF343B2C),
    Color(0xFF99C7AA),Color(0xFF9BBFD9),listOf(Color(0xFFEDB09B),Color(0xFFE8C87A),Color(0xFF9EC8D9),Color(0xFFA7BDAC))
)
private val LightPalette=StepPalette(
    Color(0xFFEDF0E8),Color(0xFFE8EDE4),Color(0xFFF5F6F0),Color(0xFFE8E5D2),Color(0xFFF2EDD9),
    Color(0xFF3F7255),Color(0xFF416F88),listOf(Color(0xFFA55D46),Color(0xFF8C6B21),Color(0xFF47778A),Color(0xFF617D69))
)
internal val LocalStepPalette=staticCompositionLocalOf {DarkPalette}
internal val StepGreen:Color @Composable get()=LocalStepPalette.current.success
internal val StepGold:Color @Composable get()=MaterialTheme.colorScheme.primary
internal val StepMuted:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant
internal val StepLine:Color @Composable get()=MaterialTheme.colorScheme.outlineVariant

@Composable fun DesktopTheme(content:@Composable ()->Unit) {
    val dark by DesktopAppearance.dark.collectAsState()
    val scheme=if(dark) darkColorScheme(
        primary=Color(0xFFE8C87A),onPrimary=Color(0xFF17211D),primaryContainer=Color(0xFF343B2C),onPrimaryContainer=Color(0xFFF6E4B5),
        secondary=Color(0xFF99C7AA),onSecondary=Color(0xFF17211D),secondaryContainer=Color(0xFF344038),onSecondaryContainer=Color(0xFFF3F0E8),
        background=Color(0xFF17211D),onBackground=Color(0xFFF3F0E8),surface=Color(0xFF27312B),onSurface=Color(0xFFF3F0E8),
        surfaceVariant=Color(0xFF344038),onSurfaceVariant=Color(0xFFBDC6C0),outline=Color(0xFF526057),outlineVariant=Color(0xFFD6DED8).copy(alpha=.10f),
        error=Color(0xFFEFA494),onError=Color(0xFF17211D),surfaceTint=Color.Transparent
    ) else lightColorScheme(
        primary=Color(0xFF80611C),onPrimary=Color(0xFFFFFCF3),primaryContainer=Color(0xFFF2EDD9),onPrimaryContainer=Color(0xFF6D531D),
        secondary=Color(0xFF3F7255),onSecondary=Color.White,secondaryContainer=Color(0xFFE8EDE4),onSecondaryContainer=Color(0xFF26392D),
        background=Color(0xFFF6F7F1),onBackground=Color(0xFF24382C),surface=Color(0xFFFFFEF9),onSurface=Color(0xFF24382C),
        surfaceVariant=Color(0xFFE8EDE4),onSurfaceVariant=Color(0xFF59695E),outline=Color(0xFFA5B0A5),outlineVariant=Color(0xFF536353).copy(alpha=.16f),
        error=Color(0xFFA7473F),surfaceTint=Color.Transparent
    )
    CompositionLocalProvider(LocalStepPalette provides if(dark)DarkPalette else LightPalette) {
        MaterialTheme(
            colorScheme=scheme,
            typography=Typography(
                displayLarge=TextStyle(fontFamily=StepFontFamily,fontSize=38.sp,fontWeight=FontWeight.Medium,lineHeight=46.sp),
                displayMedium=TextStyle(fontFamily=StepFontFamily,fontSize=32.sp,fontWeight=FontWeight.Medium,lineHeight=40.sp),
                displaySmall=TextStyle(fontFamily=StepFontFamily,fontSize=28.sp,fontWeight=FontWeight.Medium,lineHeight=36.sp),
                headlineSmall=TextStyle(fontFamily=StepFontFamily,fontSize=19.sp,fontWeight=FontWeight.Medium,lineHeight=27.sp),
                titleSmall=TextStyle(fontFamily=StepFontFamily,fontSize=13.sp,fontWeight=FontWeight.Medium,lineHeight=20.sp),
                headlineLarge=TextStyle(fontFamily=StepFontFamily,fontSize=24.sp,fontWeight=FontWeight.Medium,lineHeight=32.sp),
                headlineMedium=TextStyle(fontFamily=StepFontFamily,fontSize=21.sp,fontWeight=FontWeight.Medium,lineHeight=29.sp),
                titleLarge=TextStyle(fontFamily=StepFontFamily,fontSize=18.sp,fontWeight=FontWeight.Medium,lineHeight=26.sp),
                titleMedium=TextStyle(fontFamily=StepFontFamily,fontSize=14.sp,fontWeight=FontWeight.Medium,lineHeight=22.sp),
                bodyLarge=TextStyle(fontFamily=StepFontFamily,fontSize=15.sp,fontWeight=FontWeight.Normal,lineHeight=24.sp),
                bodyMedium=TextStyle(fontFamily=StepFontFamily,fontSize=14.sp,fontWeight=FontWeight.Medium,lineHeight=22.sp),
                bodySmall=TextStyle(fontFamily=StepFontFamily,fontSize=12.sp,fontWeight=FontWeight.Normal,lineHeight=19.sp),
                labelLarge=TextStyle(fontFamily=StepFontFamily,fontSize=13.sp,fontWeight=FontWeight.Medium,lineHeight=20.sp),
                labelMedium=TextStyle(fontFamily=StepFontFamily,fontSize=12.sp,fontWeight=FontWeight.Medium,lineHeight=18.sp),
                labelSmall=TextStyle(fontFamily=StepFontFamily,fontSize=11.sp,fontWeight=FontWeight.Medium,lineHeight=16.sp)
            ),
            shapes=Shapes(extraSmall=RoundedCornerShape(8.dp),small=RoundedCornerShape(8.dp),medium=RoundedCornerShape(14.dp),large=RoundedCornerShape(18.dp),extraLarge=RoundedCornerShape(18.dp)),
            content=content
        )
    }
}
