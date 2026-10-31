package com.pare.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Dialog layout and input, with fictional data and no persistence or network actions. */
class FocusPresetLayoutTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private var host:ComposeView?=null
    private val presets=listOf(5,15,30,60)
    private val commands=mutableListOf<ExecutionCommand>()
    @After fun dispose() {compose.runOnUiThread {host?.disposeComposition()}}

    private fun show(dialogWidthDp:Int,fontScale:Float) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.pare.app.qa")
        val id="96000000-0000-0000-0000-000000000001"
        val task=Entity("task",id,ContentActions.save(null,"task","虚构：整理一页观察笔记","",1,id,Instant.now()))
        compose.activityRule.scenario.onActivity {activity ->
            val configured=ContextThemeWrapper(activity,activity.theme).apply {
                applyOverrideConfiguration(Configuration().apply {this.fontScale=fontScale})
            }
            host=ComposeView(configured).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {PareTheme {ExecutionDialog(task,ExecutionSnapshot(),onCommand={command,result->commands.add(command);result(Result.success(Unit))},onDismiss={},modifier=Modifier.width(dialogWidthDp.dp))}}
            }
            activity.setContentView(host)
        }
        compose.waitForIdle()
    }

    private fun verifySingleRow(fontScale:Float,dialogWidthDp:Int) {
        compose.onNodeWithTag("focus_presets").performScrollTo()
        val bounds=presets.map {value ->
            val node=compose.onNodeWithTag("focus_preset_$value").assertIsDisplayed().assertHasClickAction()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val layouts=mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("${value}分",useUnmergedTree=true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layouts)}
            val layout=layouts.single()
            val rowWidth=compose.onNodeWithTag("focus_presets").fetchSemanticsNode().boundsInRoot.width/layout.layoutInput.density.density
            assertEquals("Measure actual row width, not only the host configuration",dialogWidthDp-48f,rowWidth,1f)
            assertEquals(fontScale,layout.layoutInput.density.fontScale,0.001f)
            assertEquals(1,layout.lineCount)
            // With softWrap=false, the paragraph can retain unused constraint width while
            // Text reports its tight size. Verify the rendered line, not that blank area.
            assertFalse(layout.multiParagraph.didExceedMaxLines)
            assertTrue(layout.getLineLeft(0)>=-1f)
            assertTrue("Preset $value must fit horizontally",layout.getLineRight(0)<=layout.size.width+1f)
            assertTrue(layout.getLineTop(0)>=-1f)
            assertTrue("Preset $value must fit vertically",layout.getLineBottom(0)<=layout.size.height+1f)
            node.fetchSemanticsNode().boundsInRoot
        }
        bounds.forEach {assertEquals(bounds.first().top,it.top,1f);assertEquals(bounds.first().width,it.width,1f)}
        bounds.zipWithNext().forEach {(left,right)->assertTrue("Preset hit areas must not overlap",left.right<right.left)}
        compose.onNodeWithText("10分").assertDoesNotExist()
        compose.onNodeWithText("25分").assertDoesNotExist()
    }

    private fun screenshot(name:String) {
        val directory=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        File(directory).mkdirs()
        File(directory,name).outputStream().use {assertTrue(compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it))}
    }

    @Test fun standardDialogHasFourEqualChoicesAndStartsSelectedSixtyMinutes() {
        show(320,1f);verifySingleRow(1f,320)
        compose.onNodeWithTag("focus_preset_5").assertIsSelected()
        presets.forEach {value ->
            compose.onNodeWithTag("focus_preset_$value").performClick().assertIsSelected()
            compose.onNodeWithTag("focus_minutes").assertTextContains(value.toString())
        }
        assertTrue(commands.isEmpty())
        screenshot("focus-presets-one-row.png")
        compose.onNodeWithTag("execution_primary").assertIsDisplayed().performClick()
        compose.runOnIdle {assertEquals(ExecutionAction.START_FOCUS,commands.single().action);assertEquals(60,commands.single().minutes)}
    }

    @Test fun narrowDialogWithLargeTextKeepsOneRowAndAllowsCustomDuration() {
        show(280,1.6f);verifySingleRow(1.6f,280)
        screenshot("focus-presets-large-text.png")
        compose.onNodeWithTag("focus_minutes").performScrollTo().performTextReplacement("7")
        closeSoftKeyboard()
        presets.forEach {compose.onNodeWithTag("focus_preset_$it").assertIsNotSelected()}
        compose.onNodeWithTag("execution_primary").assertIsDisplayed().performClick()
        compose.runOnIdle {assertEquals(ExecutionAction.START_FOCUS,commands.single().action);assertEquals(7,commands.single().minutes)}
    }
}
