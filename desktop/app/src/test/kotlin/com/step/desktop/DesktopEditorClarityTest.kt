package com.step.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import com.pare.app.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

/** Every editor uses a separate fictional local store and an AI client that forbids requests. */
@OptIn(ExperimentalTestApi::class)
class DesktopEditorClarityTest {
    @Test fun creatingForSelectedDayIncludesTaskWithChosenQuadrantAndEstimate() = runDesktopComposeUiTest(width=1100,height=900) {
        val runtime=fixture()
        var open by mutableStateOf(true)
        try {
            val plan=selectedPlan(runtime)
            setContent {DesktopTheme {if(open)TaskEditor(runtime,null,plan,{open=false},{},addToPlan=true,initialQuadrant=2)}}
            input("任务名称").performTextReplacement("虚构｜预约纸卡评审")
            input("任务总预估（分钟，可留空）").performTextReplacement("25")
            onNodeWithText("创建行动").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000) {!open}
            val saved=runtime.engine.state.value
            val task=saved.items.values.single {it.kind=="task"&&it.id!=TASK}
            assertEquals(2,task.content().number("quadrant"))
            assertEquals(25,task.content().number("estimatedMinutes"))
            assertTrue(DayTaskSelection.contains(saved,task.id,plan))
            assertEquals("The new task and its date membership must sync together",task.group,saved.items.getValue("day_plan:$plan").group)
            assertFalse(DayTaskSelection.contains(saved,task.id,ContentActions.planId(saved,Instant.now())))
        } finally {runtime.close()}
    }

    @Test fun preselectedQuadrantDoesNotRequireDiscardBeforeAnyTyping() = runDesktopComposeUiTest(width=1100,height=900) {
        val runtime=fixture()
        var open by mutableStateOf(true)
        try {
            setContent {DesktopTheme {if(open)TaskEditor(runtime,null,selectedPlan(runtime),{open=false},{},initialQuadrant=0)}}
            onNodeWithText("重要且紧急").assertIsSelected()
            runOnIdle {assertFalse(DesktopDraftStatus.hasUnsavedEdits.value)}
            onNodeWithText("关闭").performClick()
            runOnIdle {assertFalse(open)}
            onNodeWithText("放弃未保存的修改？").assertDoesNotExist()
        } finally {runtime.close()}
    }

    @Test fun scheduleEntryOpensSelectedDateWithoutTabHunting() = runDesktopComposeUiTest(width=1100,height=900) {
        val runtime=fixture()
        try {
            setContent {DesktopTheme {TaskEditor(runtime,TASK,selectedPlan(runtime),{},{},initialTab="schedule")}}
            input("开始时间 HH:mm").assertIsDisplayed()
            onNodeWithText("2031-04-09",substring=true).assertExists()
            runOnIdle {assertFalse(DesktopDraftStatus.hasUnsavedEdits.value)}
        } finally {runtime.close()}
    }

    @Test fun conflictedPlanRollsBackNewTaskAndKeepsTypedDraft() = runDesktopComposeUiTest(width=1100,height=900) {
        val runtime=fixture()
        var closed=false
        try {
            val plan=selectedPlan(runtime)
            runtime.edit {state->
                val included=DayTaskActions.setIncluded(state,TASK,true,plan,Instant.now())
                val day=included.items.getValue("day_plan:$plan").copy(conflictGroup="fictional-plan-conflict")
                included.copy(items=included.items+(day.key to day))
            }.getOrThrow()
            setContent {DesktopTheme {TaskEditor(runtime,null,plan,{closed=true},{},addToPlan=true)}}
            input("任务名称").performTextReplacement("虚构｜不能部分保存的行动")
            onNodeWithText("创建行动").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000) {closed || runtime.error.value!=null}
            waitForIdle()
            assertFalse("Failed day inclusion must not close the draft",closed)
            assertEquals(setOf(TASK),runtime.engine.state.value.items.values.filter {it.kind=="task"}.map {it.id}.toSet())
            assertEquals("虚构｜不能部分保存的行动",input("任务名称").editable())
        } finally {runtime.close()}
    }

    @Test fun openingLinkedTaskKeepsIdeaDraftsAndSelectedTabUntilReturning() = runDesktopComposeUiTest(width=1100,height=900) {
        val runtime=fixture()
        try {
            setContent {DesktopTheme {IdeaEditor(runtime,IDEA,selectedPlan(runtime),{},{})}}
            input("想法").performTextReplacement("虚构｜尚未保存的新标题")
            input("为什么想到它？已有的背景与线索").performTextReplacement("虚构背景草稿")
            onNodeWithText("探索记录").performClick()
            input("这次记下什么？").performTextReplacement("虚构探索草稿")
            onNodeWithText("关联行动").performClick()
            input("从想法中拆出一个具体行动").performTextReplacement("虚构未创建的行动")
            onNodeWithTag("idea_linked_task_$TASK").performClick()
            input("下一步具体做什么？").assertIsDisplayed()
            runOnIdle {assertTrue("The hidden parent draft still blocks unsafe exit",DesktopDraftStatus.hasUnsavedEdits.value)}
            closeDialog("推进这件事")
            onNodeWithText("关联行动").assertIsSelected()
            assertEquals("虚构未创建的行动",input("从想法中拆出一个具体行动").editable())
            onNodeWithText("探索记录").performClick()
            assertEquals("虚构探索草稿",input("这次记下什么？").editable())
            onNodeWithText("背景与问题").performClick()
            assertEquals("虚构｜尚未保存的新标题",input("想法").editable())
            assertEquals("虚构背景草稿",input("为什么想到它？已有的背景与线索").editable())
            assertEquals("虚构｜纸卡构想",runtime.engine.state.value.items.getValue("idea:$IDEA").title())
            onNodeWithText("关闭").performClick()
            onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        } finally {runtime.close()}
    }

    @Test fun newlyLinkedTaskOpensRealEditorAndAIUsesTaskContext() = runDesktopComposeUiTest(width=1100,height=900) {
        val runtime=fixture()
        var ideaAICalled=false
        try {
            setContent {DesktopTheme {IdeaEditor(runtime,IDEA,selectedPlan(runtime),{},{ideaAICalled=true})}}
            onNodeWithText("关联行动").performClick()
            input("从想法中拆出一个具体行动").performTextReplacement("虚构｜为纸卡写一个问题")
            onNodeWithText("创建并关联行动").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000) {runtime.engine.state.value.items.values.count {it.kind=="task"}==2}
            waitUntil(timeoutMillis=5_000) {onAllNodesWithTag("ui_saving").fetchSemanticsNodes().isEmpty()}
            waitForIdle()
            val task=runtime.engine.state.value.items.values.single {it.kind=="task"&&it.id!=TASK}
            onNodeWithTag("idea_linked_task_${task.id}").assertIsEnabled()
            onNodeWithTag("idea_open_created_task").performScrollTo().performClick()
            input("下一步具体做什么？").performTextReplacement("虚构子任务草稿")
            onNodeWithText("AI 帮我找下一步").performScrollTo().performClick()
            onNodeWithText("找到最小下一步").assertExists()
            onNodeWithText("预览将发送的内容").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000) {onAllNodes(hasText("\"taskId\":\"${task.id}\"",substring=true)).fetchSemanticsNodes().isNotEmpty()}
            onNode(hasText("\"taskId\":\"${task.id}\"",substring=true)).assertTextContains("虚构｜为纸卡写一个问题",substring=true)
            assertFalse("A linked task must never call the parent idea AI route",ideaAICalled)
            closeDialog("找到最小下一步")
            assertEquals("虚构子任务草稿",input("下一步具体做什么？").editable())
            closeDialog("推进这件事")
            onNodeWithText("继续编辑").performClick()
            assertEquals("虚构子任务草稿",input("下一步具体做什么？").editable())
        } finally {runtime.close()}
    }

    @Test fun missingAndDeletedLinkedTasksStayLinkedButCannotOpen() = runDesktopComposeUiTest(width=1100,height=900) {
        val runtime=fixture()
        try {
            runtime.edit {state->
                val idea=state.items.getValue("idea:$IDEA")
                val payload=JsonObject(idea.payload!!.toMutableMap().apply {put("content",JsonObject(idea.content()!!.toMutableMap().apply {put("linkedTaskIDs",JsonArray(listOf(TASK,MISSING).map(::JsonPrimitive)))}))})
                val linked=StateMachine.edit(state,"idea",IDEA,payload)
                StateMachine.edit(linked,"task",TASK,null,deleted=true)
            }.getOrThrow()
            setContent {DesktopTheme {IdeaEditor(runtime,IDEA,selectedPlan(runtime),{},{})}}
            onNodeWithText("关联行动").performClick()
            onNodeWithTag("idea_linked_task_$TASK").assertIsNotEnabled()
            onNodeWithTag("idea_linked_task_$MISSING").assertIsNotEnabled()
            assertEquals(listOf(TASK,MISSING),runtime.engine.state.value.items.getValue("idea:$IDEA").content().array("linkedTaskIDs").map {it.jsonPrimitive.content})
        } finally {runtime.close()}
    }

    @Test fun linkedActionShowsNextStepAndCompletionWithoutLosingItsConnection() = runDesktopComposeUiTest(width=1100,height=900) {
        val runtime=fixture()
        try {
            setContent {DesktopTheme {IdeaEditor(runtime,IDEA,selectedPlan(runtime),{},{})}}
            onNodeWithText("关联行动").performClick()
            onNodeWithText("下一步：翻开第一张纸卡").assertExists()
            runtime.setTaskCompleted(TASK,true).getOrThrow()
            waitForIdle()
            onNodeWithText("已完成").assertExists()
            onNodeWithTag("idea_linked_task_$TASK").assertIsEnabled().performClick()
            // Disabled fields intentionally have no SetText action, so match their label.
            onNodeWithText("下一步具体做什么？").assertIsNotEnabled()
            assertEquals(listOf(TASK),runtime.engine.state.value.items.getValue("idea:$IDEA").content().array("linkedTaskIDs").map {it.jsonPrimitive.content})
        } finally {runtime.close()}
    }

    @Test fun linkedTaskKeepsItsOpeningDayAndScheduleDraftAcrossParentDayChangesAndAI() = runDesktopComposeUiTest(width=1100,height=900) {
        val runtime=fixture()
        try {
            val openingPlan=selectedPlan(runtime)
            val nextPlan=openingPlan.replace("2031-04-09","2031-04-10")
            var parentPlan by mutableStateOf(openingPlan)
            setContent {DesktopTheme {IdeaEditor(runtime,IDEA,parentPlan,{},{})}}
            onNodeWithText("关联行动").performClick()
            onNodeWithTag("idea_linked_task_$TASK").performClick()
            onNodeWithText("安排与提醒").performClick()
            input("开始时间 HH:mm").performTextReplacement("09:45")
            input("时长（分钟）").performTextReplacement("25")
            runOnIdle {parentPlan=nextPlan}
            waitForIdle()
            assertEquals("09:45",input("开始时间 HH:mm").editable())
            assertEquals("25",input("时长（分钟）").editable())
            onNodeWithText("2031-04-09",substring=true).assertExists()
            onNodeWithText("2031-04-10",substring=true).assertDoesNotExist()

            onNodeWithText("最小下一步").performClick()
            onNodeWithText("AI 帮我找下一步").performScrollTo().performClick()
            onNodeWithText("预览将发送的内容").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000) {onAllNodes(hasText("\"taskId\":\"$TASK\"",substring=true)).fetchSemanticsNodes().isNotEmpty()}
            closeDialog("找到最小下一步")
            onNodeWithText("安排与提醒").performClick()
            assertEquals("09:45",input("开始时间 HH:mm").editable())
            onNodeWithText("2031-04-09",substring=true).assertExists()
            onNodeWithText("保存时间块").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000) {TimelineScheduleBaseline.from(runtime.engine.state.value,openingPlan,TASK).scheduledAt!=null}
            waitUntil(timeoutMillis=5_000) {onAllNodesWithTag("ui_saving").fetchSemanticsNodes().isEmpty()}
            val saved=TimelineScheduleBaseline.from(runtime.engine.state.value,openingPlan,TASK)
            val savedLocal=Instant.parse(requireNotNull(saved.scheduledAt)).atZone(java.time.ZoneId.of(openingPlan.substringAfter('@')))
            assertEquals("2031-04-09T09:45",savedLocal.toLocalDateTime().toString())
            assertNull(TimelineScheduleBaseline.from(runtime.engine.state.value,nextPlan,TASK).scheduledAt)

            closeDialog("推进这件事")
            onNodeWithTag("idea_linked_task_$TASK").performClick()
            onNodeWithText("安排与提醒").performClick()
            onNodeWithText("2031-04-10",substring=true).assertExists()
        } finally {runtime.close()}
    }

    @Test fun taskDetailDateActionsNameTheDayTheyActuallyChange() = runDesktopComposeUiTest(width=1100,height=900) {
        val runtime=fixture()
        try {
            val plan=selectedPlan(runtime)
            setContent {DesktopTheme {TaskEditor(runtime,TASK,plan,{},{},initialTab="detail")}}
            onNode(hasText("加入",substring=true) and hasText("4月9日",substring=true)).performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000) {DayTaskSelection.contains(runtime.engine.state.value,TASK,plan)}
            waitUntil(timeoutMillis=5_000) {onAllNodesWithTag("ui_saving").fetchSemanticsNodes().isEmpty()}
            onNode(hasText("移出",substring=true) and hasText("4月9日",substring=true)).assertExists()
            onNode(hasText("设为",substring=true) and hasText("4月9日",substring=true)).performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000) {planWin(runtime.engine.state.value,plan,TASK)}
            waitUntil(timeoutMillis=5_000) {onAllNodesWithTag("ui_saving").fetchSemanticsNodes().isEmpty()}
            onNode(hasText("取消",substring=true) and hasText("4月9日",substring=true)).assertExists()
            onNode(hasText("移出",substring=true) and hasText("4月9日",substring=true)).performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000) {!DayTaskSelection.contains(runtime.engine.state.value,TASK,plan)}
            assertFalse(planWin(runtime.engine.state.value,plan,TASK))
        } finally {runtime.close()}
    }

    private fun DesktopComposeUiTest.input(label:String)=onNode(hasSetTextAction() and hasText(label))
    private fun SemanticsNodeInteraction.editable()=fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun DesktopComposeUiTest.closeDialog(title:String) {
        onNode(hasText("关闭") and hasAnyAncestor(hasTestTag("desktop_dialog") and hasAnyDescendant(hasText(title)))).performClick()
        waitForIdle()
    }
    private fun selectedPlan(runtime:DesktopRuntime)="2031-04-09@${ContentActions.planId(runtime.engine.state.value,Instant.now()).substringAfter('@')}"
    private suspend fun fixture():DesktopRuntime {
        val runtime=DesktopRuntime(Files.createTempDirectory("step-editor-clarity-fictional-"),DesktopConfig(),{_,_->},TestProtector(),NoNetwork,FixtureClock(),MobileAIClient {_,_,_->error("Fictional editor tests never call AI")})
        try {
            runtime.initialize()
            runtime.edit {state->
                val idea=StateMachine.edit(state,"idea",IDEA,ContentActions.save(null,"idea","虚构｜纸卡构想","虚构背景",1,IDEA,Instant.now()))
                IdeaActions.createLinkedTask(idea,IDEA,TASK,"虚构｜检查纸卡","翻开第一张纸卡","",1,null,Instant.now())
            }.getOrThrow()
            return runtime
        } catch(error:Throwable) {runtime.close();throw error}
    }
    private companion object {
        const val IDEA="00000000-0000-4000-8000-000000000701"
        const val TASK="00000000-0000-4000-8000-000000000702"
        const val MISSING="00000000-0000-4000-8000-000000000703"
    }
}
