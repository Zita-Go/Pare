package com.step.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import com.pare.app.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.imageio.ImageIO

/** Real DesktopUi, rendered into a headless Compose scene. Every record is fictional. */
@OptIn(ExperimentalTestApi::class)
class DesktopWorkflowUiTest {
    @Test fun completingOneStepPreservesParentAndAllowsItsSuccessor() = runDesktopComposeUiTest(width=1280,height=900) {
        val runtime=fixtureRuntime()
        try {
            setContent {DesktopUi(runtime,{}, {}, {})}
            onNodeWithTag("nav_四象限").performClick()
            onNodeWithTag("task_$TASK").performClick()

            input("下一步具体做什么？").performTextReplacement("列出三个示例章节标题")
            input("做到什么就可以停下来？").performTextReplacement("看到三个标题就停")
            onNodeWithText("保存下一步").performScrollTo().performClick()
            awaitSaved(runtime) {it.content().str("nextAction")=="列出三个示例章节标题"}
            onNodeWithText("完成这一步").performScrollTo().performClick()
            awaitSaved(runtime) {it.content().array("completedSteps").size==1 && it.content().str("nextAction").isEmpty()}

            var task=runtime.engine.state.value.items.getValue("task:$TASK")
            assertFalse("Completing a step must leave its parent open",task.completed())
            assertEquals("列出三个示例章节标题",task.content().array("completedSteps").single().jsonObject.str("text"))
            onNodeWithText("已完成的步骤 · 1").performScrollTo().performClick()
            onNodeWithText("列出三个示例章节标题").performScrollTo().assertIsDisplayed()

            input("下一步具体做什么？").performScrollTo().performTextReplacement("给第一个示例标题写一句摘要")
            input("做到什么就可以停下来？").performTextReplacement("完成一句摘要就停")
            onNodeWithText("写下新的一步").performScrollTo().performClick()
            awaitSaved(runtime) {it.content().str("nextAction")=="给第一个示例标题写一句摘要"}
            task=runtime.engine.state.value.items.getValue("task:$TASK")
            assertFalse(task.completed())
            assertEquals(1,task.content().array("completedSteps").size)
            onNodeWithText("撤销最近这一步").performScrollTo().assertIsNotEnabled()
            captureFixture("step-history-successor","desktop_dialog")
        } finally {runtime.close()}
    }

    @Test fun contextMenuMovesTaskAndAddsItToToday() = runDesktopComposeUiTest(width=1280,height=900) {
        val runtime=fixtureRuntime()
        try {
            setContent {DesktopUi(runtime,{}, {}, {})}
            onNodeWithTag("nav_四象限").performClick()
            onNodeWithTag("task_$TASK").performMouseInput {click(button=MouseButton.Secondary)}
            onNodeWithText("移至 · 重要且紧急").performClick()
            awaitSaved(runtime) {it.content().number("quadrant")==0}
            onNodeWithTag("task_$TASK").performMouseInput {click(button=MouseButton.Secondary)}
            onNodeWithText("加入这一天").performClick()
            val planId=ContentActions.planId(runtime.engine.state.value,Instant.now())
            waitUntil(timeoutMillis=5_000) {DayTaskSelection.contains(runtime.engine.state.value,TASK,planId)}
            waitForIdle()
            assertFalse(runtime.engine.state.value.items.getValue("task:$TASK").completed())
            onNodeWithTag("nav_今天").performClick()
            onNodeWithTag("task_$TASK").assertIsDisplayed()
            captureFixture("today-selected","desktop_root")
        } finally {runtime.close()}
    }

    @Test fun ideaExplorationCanGrowIntoLinkedAction() = runDesktopComposeUiTest(width=1280,height=900) {
        val runtime=fixtureRuntime()
        try {
            setContent {DesktopUi(runtime,{}, {}, {})}
            onNodeWithTag("nav_想法").performClick()
            onNodeWithTag("idea_$IDEA").performClick()
            onNodeWithText("探索记录").performClick()
            input("这次记下什么？").performTextReplacement("示例发现：先用纸卡验证阅读流程")
            onNodeWithText("发现").performClick()
            onNodeWithText("加入探索记录").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000) {runtime.engine.state.value.items.getValue("idea:$IDEA").content().array("entries").size==1}
            waitForIdle()
            val entry=runtime.engine.state.value.items.getValue("idea:$IDEA").content().array("entries").single().jsonObject
            assertEquals("finding",entry.str("kind"))
            assertEquals("示例发现：先用纸卡验证阅读流程",entry.str("text"))

            onNodeWithText("关联行动").performScrollTo().performClick()
            input("从想法中拆出一个具体行动").performTextReplacement("示例行动：画三张阅读流程纸卡")
            onNodeWithText("创建并关联行动").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000) {runtime.engine.state.value.items.getValue("idea:$IDEA").content().array("linkedTaskIDs").size==1}
            waitForIdle()
            val state=runtime.engine.state.value
            val linked=state.items.getValue("idea:$IDEA").content().array("linkedTaskIDs").single().jsonPrimitive.content
            val task=state.items.getValue("task:$linked")
            assertEquals("示例行动：画三张阅读流程纸卡",task.title())
            assertFalse(task.completed())
            assertEquals("seed",state.items.getValue("idea:$IDEA").content().str("stage"))
            assertEquals(1,state.items.getValue("idea:$IDEA").content().array("entries").size)
            onNodeWithText("示例行动：画三张阅读流程纸卡").assertIsDisplayed()
            captureFixture("idea-linked-action","desktop_dialog")
        } finally {runtime.close()}
    }

    @Test fun closingDirtyEditorRequiresExplicitDiscardAndCancelRetainsDraft() = runDesktopComposeUiTest(width=1280,height=900) {
        val runtime=fixtureRuntime()
        try {
            setContent {DesktopUi(runtime,{}, {}, {})}
            onNodeWithTag("nav_四象限").performClick()
            onNodeWithTag("task_$TASK").performClick()
            runOnIdle {assertFalse(DesktopDraftStatus.hasUnsavedEdits.value)}
            input("下一步具体做什么？").performTextReplacement("尚未保存的示例下一步")
            runOnIdle {assertTrue(DesktopDraftStatus.hasUnsavedEdits.value)}
            onNodeWithText("关闭").performClick()
            onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
            onNodeWithText("继续编辑").performClick()
            assertEquals("尚未保存的示例下一步",input("下一步具体做什么？").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertEquals("打开示例笔记",runtime.engine.state.value.items.getValue("task:$TASK").content().str("nextAction"))
            onNodeWithText("关闭").performClick()
            onNodeWithText("放弃修改").performClick()
            onNodeWithTag("desktop_dialog").assertDoesNotExist()
            runOnIdle {assertFalse(DesktopDraftStatus.hasUnsavedEdits.value)}
            assertEquals("打开示例笔记",runtime.engine.state.value.items.getValue("task:$TASK").content().str("nextAction"))
        } finally {runtime.close()}
    }

    @Test fun quickCaptureKeepsSaveVisibleWithLongDraftAndPersistenceError() = runDesktopComposeUiTest(width=460,height=400) {
        val runtime=fixtureRuntime()
        try {
            setContent {QuickCaptureUi(runtime,{})}
            val draft=(1..10).joinToString("\n") {"虚构的第${it}行待整理想法"}
            input("此刻想到什么？").performTextReplacement(draft)
            onNodeWithTag("quick_capture_save").assertIsDisplayed()
            // Only this isolated fixture directory is obstructed; no real data is touched.
            val backup=runtime.home.resolve("sync-state.json.bak")
            Files.deleteIfExists(backup)
            Files.createDirectory(backup)
            Files.writeString(backup.resolve("fixture-blocker"),"fictional test obstruction")
            onNodeWithTag("quick_capture_save").performClick()
            waitUntil(timeoutMillis=5_000) {runtime.error.value!=null}
            waitUntil(timeoutMillis=5_000) {onAllNodesWithTag("ui_saving").fetchSemanticsNodes().isEmpty()}
            onNodeWithTag("quick_capture_save").assertIsDisplayed().assertIsEnabled()
            assertEquals(draft,input("此刻想到什么？").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertTrue(runtime.engine.state.value.items.values.none {it.kind=="inbox"&&!it.deleted})
        } finally {runtime.close()}
    }

    @Test fun repeatedQuickCaptureUsesANewIdentityAfterEachSuccessfulSave() = runDesktopComposeUiTest(width=640,height=520) {
        val runtime=fixtureRuntime()
        var visible by mutableStateOf(true)
        try {
            setContent {QuickCaptureUi(runtime,{visible=false},visible)}
            input("此刻想到什么？").performTextReplacement("示例收件一：记录纸卡构想")
            onNodeWithText("保存到收件箱").performClick()
            waitUntil(timeoutMillis=5_000) {!visible}
            val first=runtime.engine.state.value.items.values.single {it.kind=="inbox"&&!it.deleted}
            assertEquals("示例收件一：记录纸卡构想",first.title())

            runOnIdle {visible=true}
            waitForIdle()
            assertEquals("",input("此刻想到什么？").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            input("此刻想到什么？").performTextReplacement("示例收件二：记下验证方式")
            onNodeWithText("保存到收件箱").performClick()
            waitUntil(timeoutMillis=5_000) {!visible}
            val captures=runtime.engine.state.value.items.values.filter {it.kind=="inbox"&&!it.deleted}
            runOnIdle {assertFalse(DesktopDraftStatus.hasUnsavedEdits.value)}
            assertEquals(2,captures.size)
            assertEquals(2,captures.map {it.id}.distinct().size)
            assertEquals(setOf("示例收件一：记录纸卡构想","示例收件二：记下验证方式"),captures.map {it.title()}.toSet())
        } finally {runtime.close()}
    }

    @Test fun hidingQuickCaptureRetainsAnUnsubmittedDraftUntilReopened() = runDesktopComposeUiTest(width=640,height=520) {
        val runtime=fixtureRuntime()
        var visible by mutableStateOf(true)
        try {
            // The same composition remains mounted, as it does in a hidden tray window.
            setContent {QuickCaptureUi(runtime,{visible=false},visible)}
            input("此刻想到什么？").performTextReplacement("示例草稿：还没有整理完的阅读灵感")
            onNodeWithText("取消").performClick()
            runOnIdle {assertFalse(visible);assertTrue(DesktopDraftStatus.hasUnsavedEdits.value)}
            assertTrue(runtime.engine.state.value.items.values.none {it.kind=="inbox"&&!it.deleted})
            runOnIdle {visible=true}
            waitForIdle()
            assertEquals("示例草稿：还没有整理完的阅读灵感",input("此刻想到什么？").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            onNodeWithText("保存到收件箱").performClick()
            waitUntil(timeoutMillis=5_000) {!visible}
            assertEquals("示例草稿：还没有整理完的阅读灵感",runtime.engine.state.value.items.values.single {it.kind=="inbox"&&!it.deleted}.title())
        } finally {runtime.close()}
    }

    private fun DesktopComposeUiTest.input(label:String) = onNode(hasSetTextAction() and hasText(label))

    private fun DesktopComposeUiTest.awaitSaved(runtime:DesktopRuntime,predicate:(Entity)->Boolean) {
        waitUntil(timeoutMillis=5_000) {predicate(runtime.engine.state.value.items.getValue("task:$TASK"))}
        waitUntil(timeoutMillis=5_000) {onAllNodesWithTag("ui_saving").fetchSemanticsNodes().isEmpty()}
        waitForIdle()
    }

    private fun DesktopComposeUiTest.captureFixture(name:String,tag:String) {
        // The filename labels the actual rendering host. A Mac render is never Windows evidence.
        val host=System.getProperty("os.name").lowercase().replace(Regex("[^a-z0-9]+"),"-")
        val output=Path.of("build","test-screenshots")
        Files.createDirectories(output)
        assertTrue(ImageIO.write(onNodeWithTag(tag).captureToImage().toAwtImage(),"png",output.resolve("headless-$host-$name.png").toFile()))
    }

    private suspend fun fixtureRuntime():DesktopRuntime {
        val runtime=DesktopRuntime(
            Files.createTempDirectory("step-fictional-ui-"),DesktopConfig(),{_,_->},TestProtector(),
            UiNoNetwork,FixtureClock(),MobileAIClient {_,_,_->error("UI fixture must never request AI")}
        )
        try {
            runtime.initialize()
            val now=Instant.now()
            runtime.edit {state->
                var result=StateMachine.edit(state,"task",TASK,ContentActions.save(null,"task","示例｜整理读书笔记","离屏测试专用虚构内容",1,TASK,now,nextAction="打开示例笔记"))
                result=StateMachine.edit(result,"idea",IDEA,ContentActions.save(null,"idea","示例｜阅读记录工具","虚构想法，用来验证探索与关联行动。",1,IDEA,now))
                result
            }.getOrThrow()
            return runtime
        } catch(error:Throwable) {runtime.close();throw error}
    }

    private companion object {
        const val TASK="00000000-0000-4000-8000-000000000101"
        const val IDEA="00000000-0000-4000-8000-000000000102"
    }
}

private object UiNoNetwork:SyncRemote {
    override suspend fun login(email:String,password:String,signup:Boolean):Session?=error("UI fixture forbids network")
    override suspend fun refresh(session:Session):Session=error("UI fixture forbids network")
    override suspend fun pull(session:Session,cursor:Long):Page=error("UI fixture forbids network")
    override suspend fun push(session:Session,request:Push):Reply=error("UI fixture forbids network")
    override suspend fun logout(session:Session)=error("UI fixture forbids network")
}
