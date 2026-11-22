package com.pare.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualContrastTest {
    private fun ratio(a:Color,b:Color):Float {
        val high=maxOf(a.luminance(),b.luminance());val low=minOf(a.luminance(),b.luminance())
        return (high+0.05f)/(low+0.05f)
    }
    @Test fun ordinaryAndSecondaryTextRemainReadableOnEveryUsedSurface() {
        for((name,palette) in listOf("light" to LightPalette,"dark" to DarkPalette)) {
            val backgrounds=listOf(palette.background,palette.surface,palette.surfaceContainerLow,palette.surfaceContainer,palette.surfaceContainerHigh,palette.surfaceContainerHighest)
            for(background in backgrounds)for(text in listOf(palette.onSurface,palette.onSurfaceVariant)) {
                assertTrue("$name secondary/body contrast ${ratio(text,background)} below 4.5",ratio(text,background)>=4.5f)
            }
        }
    }
    @Test fun PrimaryAndFocusActionLabelsMeetTextContrast() {
        for(p in listOf(LightPalette,DarkPalette))for((text,background) in listOf(
            p.onPrimary to p.primary,p.onPrimaryContainer to p.primaryContainer,p.secondary to p.secondaryContainer,
            p.onSecondaryContainer to p.secondaryContainer,p.onErrorContainer to p.errorContainer)) {
            assertTrue("Action label contrast ${ratio(text,background)} below 4.5",ratio(text,background)>=4.5f)
        }
    }
}
