package com.pare.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Real UI, alarm PendingIntents and notifications, strictly within the isolated QA package. */
@RunWith(AndroidJUnit4::class)
class AndroidCapabilitiesFlowTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private lateinit var scenario:ActivityScenario<MainActivity>
    private lateinit var scenarioLaunchIntent:Intent
    private lateinit var runtime:SyncRuntime
    private var lifecycleRegistered=false
    private val createdActivities=AtomicInteger()
    private val lifecycleEvents=mutableListOf<String>()
    private val lifecycleCallback=ActivityLifecycleCallback {activity,stage ->
        if(activity is MainActivity && activity.packageName=="com.pare.app.qa") {
            if(stage==Stage.CREATED) createdActivities.incrementAndGet()
            val event="transition stage=$stage instance=${System.identityHashCode(activity)} taskId=${activity.taskId}"
            synchronized(lifecycleEvents) {lifecycleEvents.add(event)}
            Log.i("PareCapabilitiesLifecycle",event)
        }
    }
    private val reminders get()=MobileNotificationRuntime(context)
    private fun read()=PareRepository(context).use {it.read()}
    private fun execution()=ExecutionFilePersistence(context).load()

    @Before fun launchIsolatedOfflineQA() {
        check(context.packageName=="com.pare.app.qa") {"Capability tests must never use the production package"}
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycleCallback)
        lifecycleRegistered=true
        check(context.getSharedPreferences("session-vault",Context.MODE_PRIVATE).edit().clear().commit())
        val blank=PareRepository(context).use {it.update {SyncState()}}
        ExecutionFilePersistence(context).save(ExecutionSnapshot(device=blank.device))
        reminders.refresh(blank,force=true)
        runtime=SyncRuntime.get(context)
        runBlocking {runtime.engine.initialize();runtime.engine.pause()}
        scenario=ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {scenarioLaunchIntent=Intent(it.intent)}
        visible("tab_today")
        compose.waitUntil(10_000) {runtime.executionReady.value}
        runtime.execution.reconcile()
        compose.onNodeWithTag("tab_today").performClick()
    }

    @After fun removeOnlyQAFixturesAndTheirLocalAlarms() {
        try {
            if(::scenario.isInitialized) closeScenarioAndVerifyLifecycle()
        } finally {
            try {
                if(::runtime.isInitialized) {
                    // Empty sources cancel registered alarms and visible notifications even if a UI assertion failed.
                    val blank=runtime.engine.edit {SyncState(device=it.device)}
                    if(runtime.executionReady.value) runtime.execution.reconcile()
                    ExecutionFilePersistence(context).save(ExecutionSnapshot(device=blank.device))
                    reminders.refresh(blank,force=true)
                    assertTrue(reminders.readLedger().scheduled.isEmpty())
                    assertTrue(reminders.readLedger().visible.isEmpty())
                }
            } finally {
                if(lifecycleRegistered) ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycleCallback)
            }
        }
    }

    private data class ActivityRecord(val activity:MainActivity,val stage:Stage,val taskId:Int)
    private fun liveQAActivities():List<ActivityRecord> {
        var records=emptyList<ActivityRecord>()
        instrumentation.runOnMainSync {
            val monitor=ActivityLifecycleMonitorRegistry.getInstance()
            records=Stage.values().filter {it!=Stage.DESTROYED}.flatMap {stage ->
                monitor.getActivitiesInStage(stage).filterIsInstance<MainActivity>()
                    .filter {it.packageName=="com.pare.app.qa"}.map {ActivityRecord(it,stage,it.taskId)}
            }
        }
        return records
    }
    private fun lifecycleSnapshot(label:String):List<ActivityRecord> {
        instrumentation.waitForIdleSync()
        val records=liveQAActivities()
        val counts=Stage.values().filter {it!=Stage.DESTROYED}.associateWith {stage ->records.count {it.stage==stage}}
        val detail="$label counts=$counts createdTotal=${createdActivities.get()} " +
            records.joinToString {"${it.activity.javaClass.name}@${System.identityHashCode(it.activity)} taskId=${it.taskId} stage=${it.stage}"}
        Log.i("PareCapabilitiesLifecycle",detail)
        val history=synchronized(lifecycleEvents) {lifecycleEvents.joinToString("\n")}
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let {directory ->
            val output=File(directory).apply {mkdirs()}
            File(output,"35-capability-activity-lifecycle.txt").appendText("$detail\n$history\n\n")
        }
        return records
    }
    private fun singleResumedActivity(label:String):ActivityRecord {
        val records=lifecycleSnapshot(label)
        assertEquals("Exactly one QA MainActivity must remain: $records",1,records.size)
        assertEquals("No extra CREATED/STARTED/PAUSED activity is allowed: $records",Stage.RESUMED,records.single().stage)
        assertTrue(records.single().taskId>=0)
        return records.single()
    }
    private fun closeScenarioAndVerifyLifecycle() {
        val before=lifecycleSnapshot("before_cleanup")
        // MainActivity correctly adopts a notification's Intent in onNewIntent. ActivityScenario
        // filters callbacks against its launcher Intent, so restore that test-only identity AFTER
        // asserting the real deep-link behavior; do not change production flags or hide close errors.
        if(::scenarioLaunchIntent.isInitialized) instrumentation.runOnMainSync {
            before.forEach {it.activity.intent=Intent(scenarioLaunchIntent)}
        }
        try {
            scenario.close()
        } finally {
            // Also finish unexpected QA instances on assertion failure, while retaining the failure.
            val remaining=liveQAActivities()
            instrumentation.runOnMainSync {remaining.forEach {it.activity.finish()}}
            compose.waitUntil(5_000) {liveQAActivities().isEmpty()}
            assertTrue("QA MainActivity must be fully destroyed after teardown",lifecycleSnapshot("after_cleanup").isEmpty())
        }
    }

    private fun visible(tag:String) {
        compose.waitUntil(10_000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
    }
    private fun gone(tag:String) {
        compose.waitUntil(10_000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()}
    }
    private fun dialogButton(text:String)=compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog()))
    private fun waitForDialogButton(text:String) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun waitForNoDialog() {
        compose.waitUntil(10_000) {compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()}
    }
    private fun capture(node:SemanticsNodeInteraction,name:String) {
        compose.waitForIdle()
        val directory=File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")) {
            "Pass additionalTestOutputDir to retain native-resolution QA screenshots"
        }).apply {mkdirs()}
        val bitmap=node.captureToImage().asAndroidBitmap()
        assertTrue(bitmap.width>0 && bitmap.height>0)
        File(directory,name).outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
    }
    private fun createTodayTask(title:String,nextAction:String):String {
        visible("add_item");compose.onNodeWithTag("add_item").performClick();visible("editor_text")
        compose.onNodeWithTag("editor_text").performTextReplacement(title)
        // Let the IME finish changing this bottom sheet's bounds before targeting the details toggle.
        // Opening a second task can overlap the previous keyboard's hide with the new title's show.
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        val nextActionCount=compose.onAllNodesWithTag("editor_next_action").fetchSemanticsNodes().size
        val expandedToggleCount=compose.onAllNodes(hasTestTag("editor_details") and hasText("收起更多信息")).fetchSemanticsNodes().size
        Log.i("PareCapabilitiesEditor","before_details title=$title nextActionCount=$nextActionCount expandedToggleCount=$expandedToggleCount")
        if(nextActionCount==0 && expandedToggleCount==0) {
            compose.onNodeWithTag("editor_details").performScrollTo().assertIsDisplayed().performClick()
        }
        // Wait for the actual expanded composition, without clicking a toggle twice on a slow frame.
        visible("editor_next_action")
        compose.onNodeWithTag("editor_details").assertTextContains("收起更多信息")
        compose.onNodeWithTag("editor_next_action").performScrollTo().performTextReplacement(nextAction)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("editor_save").performClick();gone("editor_save")
        val saved=read().items.values.single {it.kind=="task" && it.title()==title}
        assertEquals("The UI-created task must retain its specific next action",nextAction,saved.content()!!["nextAction"]!!.jsonPrimitive.content)
        return saved.id
    }
    private fun openMenuAction(taskId:String,action:String) {
        compose.onNodeWithTag("main_list").performScrollToNode(hasTestTag("task_$taskId"))
        compose.onNodeWithTag("more_$taskId").performClick()
        visible("${action}_$taskId");compose.onNodeWithTag("${action}_$taskId").performClick()
    }
    private fun openRun(taskId:String) {
        openMenuAction(taskId,"run")
        compose.waitUntil(10_000) {compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()}
    }
    private fun finishRunFromDialog() {
        dialogButton("结束并记录").performClick();waitForDialogButton("确认")
        dialogButton("确认").performClick();waitForNoDialog()
    }

    @Test fun focusSurvivesPauseRotationAndCoexistsWithMultipleExternalRuns() {
        val focusId=createTodayTask("写下虚构旅行清单前三项","打开空白笔记，先写证件")
        val externalId=createTodayTask("等待虚构资料的整理结果","检查示例输出是否包含三个标题")
        val secondExternalId=createTodayTask("等待虚构封面草稿","检查示例图片是否有标题留白")

        openRun(focusId);waitForDialogButton("开始专注")
        dialogButton("开始专注").performClick();waitForNoDialog()
        compose.waitUntil(10_000) {execution()?.focus?.taskId==focusId}
        val originalRun=execution()!!.focus!!
        assertFalse(originalRun.paused)
        assertEquals(5,originalRun.targetMinutes)
        openRun(focusId);waitForDialogButton("暂停")
        capture(compose.onNode(isDialog()),"31-personal-focus-running.png")
        dialogButton("暂停").performScrollTo().performClick()
        compose.waitUntil(10_000) {execution()?.focus?.paused==true}
        val paused=execution()!!.focus!!
        scenario.recreate();waitForDialogButton("继续")
        val restored=execution()!!.focus!!
        assertEquals(originalRun.id,restored.id)
        assertEquals(paused.accumulatedMillis,restored.accumulatedMillis)
        assertTrue(restored.paused)
        capture(compose.onNode(isDialog()),"32-personal-focus-restored.png")
        dialogButton("继续").performScrollTo().performClick()
        compose.waitUntil(10_000) {execution()?.focus?.paused==false}
        assertEquals(paused.deadlineGeneration+1,execution()!!.focus!!.deadlineGeneration)
        dialogButton("关闭").performClick();waitForNoDialog()

        for(id in listOf(externalId,secondExternalId)) {
            openRun(id);dialogButton("外部推进").performScrollTo().performClick()
            dialogButton("记录开始").performClick();waitForNoDialog()
            compose.waitUntil(10_000) {execution()?.external?.any {it.taskId==id}==true}
        }
        assertEquals(originalRun.id,execution()!!.focus!!.id)
        assertEquals(setOf(externalId,secondExternalId),execution()!!.external.map {it.taskId}.toSet())
        openRun(externalId);waitForDialogButton("结束并记录")
        capture(compose.onNode(isDialog()),"33-external-parallel-run.png")
        finishRunFromDialog()
        val external=read().items.getValue("task:$externalId")
        assertEquals(1,external.content()!!["parallelRunSessions"]!!.jsonArray.size)
        assertNull(external.content()!!["actualMinutes"])
        assertNull(external.content()!!["progressSessions"])
        assertFalse(external.completed())
        assertEquals(originalRun.id,execution()!!.focus!!.id)
        assertEquals(secondExternalId,execution()!!.external.single().taskId)

        openRun(secondExternalId);dialogButton("取消这段记录").performScrollTo().performClick()
        waitForDialogButton("确认");dialogButton("确认").performClick();waitForNoDialog()
        assertTrue(execution()!!.external.isEmpty())
        assertNull(read().items.getValue("task:$secondExternalId").content()!!["parallelRunSessions"])
        openRun(focusId);finishRunFromDialog()
        compose.waitUntil(10_000) {execution()?.focus==null}
        val focus=read().items.getValue("task:$focusId")
        val session=focus.content()!!["progressSessions"]!!.jsonArray.single().jsonObject
        assertEquals(originalRun.id,session["id"]!!.jsonPrimitive.content)
        assertTrue(session["minutes"]!!.jsonPrimitive.int>=1)
        assertEquals(session["minutes"]!!.jsonPrimitive.int,focus.content()!!["actualMinutes"]!!.jsonPrimitive.int)
        assertFalse(focus.completed())
        assertTrue(read().owner==null && !read().enabled && read().pending==null)
    }

    @Test fun tenMinuteReminderRegistersAlarmAndPrivateNotificationOpensItsTask() {
        // Grant only this test package; Android must not expose any production account or task here.
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
        }
        reminders.createChannel()
        val title="十分钟后检查虚构阅读笔记"
        val taskId=createTodayTask(title,"检查示例笔记里的一句话")
        createTodayTask("另一项虚构任务，通知不应打开它","列出一项示例材料")
        openMenuAction(taskId,"remind");visible("task_reminder_dialog")
        val before=Instant.now()
        compose.onNodeWithTag("reminder_after_10").performClick()
        capture(compose.onNodeWithTag("task_reminder_dialog"),"34-task-reminder-ten-minutes.png")
        compose.onNodeWithTag("reminder_save").performClick();gone("task_reminder_dialog")
        val at=Instant.parse(read().items.getValue("task:$taskId").content()!!["remindAt"]!!.jsonPrimitive.content)
        assertTrue(at>=before.plusSeconds(590) && at<=Instant.now().plusSeconds(610))

        // A manually disabled OS notification channel is respected; this optional delivery test is reported skipped.
        assumeTrue("QA device does not currently allow the Pare notification channel",reminders.notificationsAllowed())
        reminders.refresh(read())
        val event=reminders.readLedger().scheduled.values.single {it.taskId==taskId && it.kind=="task"}
        assertNull(reminders.lastError())
        val alarmIntent=Intent(context,ReminderAlarmReceiver::class.java).setAction(MobileNotificationRuntime.ACTION_FIRE)
            .setData(Uri.parse("pare-reminder://${context.packageName}/${MobileNotificationRuntime.ACTION_FIRE}/${Uri.encode(event.token)}"))
        val alarm=PendingIntent.getBroadcast(context,0,alarmIntent,PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        assertNotNull("Successful scheduling must retain the matching alarm PendingIntent",alarm)
        assertEquals(context.packageName,alarm!!.creatorPackage)

        val fireAt=Instant.ofEpochMilli(event.atMillis).plusSeconds(1)
        reminders.handle(MobileNotificationRuntime.ACTION_FIRE,event.token,fireAt)
        val manager=context.getSystemService(NotificationManager::class.java)
        compose.waitUntil(10_000) {manager.activeNotifications.any {it.tag==event.token}}
        val posted=manager.activeNotifications.single {it.tag==event.token}.notification
        assertEquals(R.drawable.pare_notification,posted.smallIcon.resId)
        val iconBitmap=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888)
        context.getDrawable(R.drawable.pare_notification)!!.apply {setBounds(0,0,24,24);draw(android.graphics.Canvas(iconBitmap))}
        assertEquals("Notification mark must have a transparent background",0,android.graphics.Color.alpha(iconBitmap.getPixel(0,0)))
        assertTrue("The Pare mark must have visible strokes",android.graphics.Color.alpha(iconBitmap.getPixel(6,10))>0)
        assertEquals(Notification.VISIBILITY_PRIVATE,posted.visibility)
        assertEquals(title,posted.extras.getString(Notification.EXTRA_TEXT))
        assertNotNull(posted.publicVersion)
        assertFalse(posted.publicVersion.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains(title))
        assertEquals(context.packageName,posted.contentIntent.creatorPackage)
        assertTrue(event.token in reminders.readLedger().handled)

        val beforeClick=singleResumedActivity("before_notification_click")
        val createdBeforeClick=createdActivities.get()
        posted.contentIntent.send()
        visible("editor_text")
        compose.onNodeWithTag("editor_text").assertTextContains(title)
        val afterClick=singleResumedActivity("after_notification_click")
        assertSame("A notification must reuse the current MainActivity",beforeClick.activity,afterClick.activity)
        assertEquals("A notification must stay in the current QA task",beforeClick.taskId,afterClick.taskId)
        assertEquals("No transient duplicate MainActivity may be created",createdBeforeClick,createdActivities.get())
        compose.onNodeWithTag("editor_cancel").performClick();gone("editor_text")
        singleResumedActivity("after_notification_editor_closed")
        assertFalse(read().items.getValue("task:$taskId").completed())
    }
}
