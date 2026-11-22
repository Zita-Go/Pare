package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.pare.app.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

@OptIn(ExperimentalTestApi::class)
class DesktopLibraryUiTest {
    private val id="00000000-0000-4000-8000-000000001581"
    @Test fun completedTaskCanBeFoundArchivedRestoredAndRecoveredFromTrash()=runDesktopComposeUiTest(width=1280,height=900) {
        val runtime=fixture()
        try {
            runtime.setTaskCompleted(id,true).getOrThrow()
            setContent {DesktopTheme {Column(Modifier.fillMaxSize()){LibraryPane(runtime){_,_->}}}}
            onNodeWithTag("library_scope_COMPLETED").performClick()
            onNodeWithTag("library_$id").assertExists()
            onNodeWithTag("library_archive_$id").performClick()
            onNodeWithText("取消").performClick()
            assertFalse(LibrarySearch.archived(runtime.engine.state.value.items.getValue("task:$id")))
            onNodeWithTag("library_archive_$id").performClick();onNodeWithTag("library_confirm").performClick()
            waitUntil(timeoutMillis=5_000){LibrarySearch.archived(runtime.engine.state.value.items.getValue("task:$id"))}
            onNodeWithTag("library_scope_ARCHIVED").performClick();onNodeWithTag("library_unarchive_$id").performClick()
            waitUntil(timeoutMillis=5_000){!LibrarySearch.archived(runtime.engine.state.value.items.getValue("task:$id"))}
            onNodeWithTag("library_scope_TASKS").performClick();onNodeWithTag("library_delete_$id").performClick();onNodeWithTag("library_confirm").performClick()
            waitUntil(timeoutMillis=5_000){runtime.engine.state.value.items.getValue("task:$id").deleted}
            onNodeWithTag("library_scope_TRASH").performClick();onNodeWithTag("trash_restore_$id").performClick()
            waitUntil(timeoutMillis=5_000){!runtime.engine.state.value.items.getValue("task:$id").deleted}
            assertEquals("打开虚构资料",runtime.engine.state.value.items.getValue("task:$id").content().str("nextAction"))
            assertEquals(1,runtime.engine.state.value.localTrash.count {it.restoredKey=="task:$id"})
        } finally {runtime.close()}
    }
    @Test fun metadataEditorSavesRealFieldsWithoutCompletingOrSchedulingTask()=runDesktopComposeUiTest(width=1280,height=900) {
        val runtime=fixture()
        try {
            setContent {DesktopTheme {TaskEditor(runtime,id,ContentActions.planId(runtime.engine.state.value,Instant.now()),{}, {})}}
            onNodeWithText("任务详情").performClick()
            onNode(hasSetTextAction() and hasText("任务总预估（分钟）")).performScrollTo().performTextReplacement("60")
            onNode(hasSetTextAction() and hasText("累计实际投入（分钟）")).performScrollTo().performTextReplacement("12")
            onNode(hasSetTextAction() and hasText("截止时间 yyyy-MM-dd HH:mm（留空清除）")).performScrollTo().performTextReplacement("2026-12-01 17:00")
            onNodeWithTag("task_metadata_save").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000){runtime.engine.state.value.items.getValue("task:$id").content().number("estimatedMinutes")==60}
            val t=runtime.engine.state.value.items.getValue("task:$id")
            assertEquals(12,t.content().number("actualMinutes"));assertFalse(t.completed())
            assertTrue(t.content().str("dueAt").isNotEmpty())
            assertTrue(runtime.engine.state.value.items.values.none {it.kind=="day_plan"})
        } finally {runtime.close()}
    }
    @Test fun reviewExportContainsCountsButNoTaskTitles()=runDesktopComposeUiTest(width=1280,height=900) {
        val runtime=fixture()
        try {
            runtime.setTaskCompleted(id,true).getOrThrow()
            setContent {DesktopTheme {Column(Modifier.fillMaxSize()){ReviewPane(runtime){}}}}
            onNodeWithTag("review_export").performClick()
            onNodeWithTag("review_export_content").assertTextContains("最近7天：完成 1 件",substring=true)
            onNodeWithTag("review_export_content").assert(hasText("示例｜资料库任务",substring=true).not())
        } finally {runtime.close()}
    }
    private suspend fun fixture():DesktopRuntime {
        val runtime=DesktopRuntime(Files.createTempDirectory("step-library-fixture-"),DesktopConfig(),{_,_->},TestProtector(),LibraryNoNetwork,FixtureClock().apply {time=time.copy(wallMillis=Instant.now().toEpochMilli())},MobileAIClient {_,_,_->error("No fixture AI")})
        runtime.initialize()
        runtime.edit {StateMachine.edit(it,"task",id,ContentActions.save(null,"task","示例｜资料库任务","只有虚构数据",1,id,Instant.now(),nextAction="打开虚构资料"))}.getOrThrow()
        return runtime
    }
}
private object LibraryNoNetwork:SyncRemote {
    override suspend fun login(email:String,password:String,signup:Boolean):Session?=error("No fixture network")
    override suspend fun refresh(session:Session):Session=error("No fixture network")
    override suspend fun pull(session:Session,cursor:Long):Page=error("No fixture network")
    override suspend fun push(session:Session,request:Push):Reply=error("No fixture network")
    override suspend fun logout(session:Session)=error("No fixture network")
}
