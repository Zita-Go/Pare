package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DesktopMaterialInteractionTest {
    @Test fun hoverAndPressChangePaintWithoutMovingTheActionOrInvokingItTwice() = runDesktopComposeUiTest(width=320,height=150) {
        var clicks=0
        DesktopAppearance.dark.value=true
        setContent {DesktopTheme {
            Box(Modifier.fillMaxSize().stepWindowBackground(),contentAlignment=Alignment.Center) {
                StepButton({clicks++},modifier=Modifier.testTag("material_action")) {Text("规划这一天")}
            }
        }}
        val node=onNodeWithTag("material_action")
        val bounds=node.fetchSemanticsNode().boundsInRoot
        val before=save(node,"rest")
        node.performMouseInput {enter(center)}
        mainClock.advanceTimeBy(240);waitForIdle()
        assertEquals(bounds,node.fetchSemanticsNode().boundsInRoot)
        val hovered=save(node,"hover")
        assertNotEquals("Hover should provide visible paint feedback",before,hovered)
        node.performMouseInput {press()}
        mainClock.advanceTimeBy(160);waitForIdle()
        assertEquals(bounds,node.fetchSemanticsNode().boundsInRoot)
        assertEquals(0,clicks)
        assertNotEquals("Press should provide distinct feedback",hovered,save(node,"pressed"))
        node.performMouseInput {release()}
        waitForIdle();assertEquals(1,clicks)
    }
    private fun save(node:SemanticsNodeInteraction,name:String):Int {
        val image=node.captureToImage().toAwtImage()
        val host=System.getProperty("os.name").lowercase().replace(Regex("[^a-z0-9]+"),"-")
        val dir=Path.of("build","test-screenshots");Files.createDirectories(dir)
        ImageIO.write(image,"png",dir.resolve("material-$host-button-$name.png").toFile())
        return image.getRGB(0,0,image.width,image.height,null,0,image.width).contentHashCode()
    }
}
