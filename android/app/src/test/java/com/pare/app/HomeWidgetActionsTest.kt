package com.pare.app

import android.content.Context
import android.content.Intent
import android.app.Application
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class HomeWidgetActionsTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val task="00000000-0000-4000-8000-000000000001"
    private val run="00000000-0000-4000-8000-000000000002"
    @Test fun explicitImmutableIntentsAreDistinctAcrossWidgetsAndRunIdentities() {
        val request=HomeWidgetRequest(HomeWidgetAction.PAUSE_FOCUS,task,run)
        val one=HomeWidgetActions.pendingIntent(context,1,request)
        val two=HomeWidgetActions.pendingIntent(context,2,request)
        val three=HomeWidgetActions.pendingIntent(context,1,request.copy(runId="00000000-0000-4000-8000-000000000003"))
        assertNotEquals(one,two);assertNotEquals(one,three)
        assertTrue(one.isImmutable)
        val intent=shadowOf(one).savedIntent
        val identity=Intent(intent)
        assertEquals(MainActivity::class.java.name,intent.component?.className)
        assertEquals(request,HomeWidgetActions.consume(intent,context.packageName))
        assertNull(HomeWidgetActions.consume(intent,context.packageName))
        assertTrue("Consumed extras must not change activity launch identity",identity.filterEquals(intent))
    }
    @Test fun malformedAndUnscopedRoutesAreRejected() {
        assertNull(HomeWidgetActions.consume(Intent().putExtra(HomeWidgetActions.ACTION_EXTRA,"CAPTURE"),context.packageName))
        val intent=HomeWidgetActions.intent(context,1,HomeWidgetRequest(HomeWidgetAction.PAUSE_FOCUS,task,run))
        intent.putExtra(HomeWidgetActions.RUN_EXTRA,"not-a-run")
        assertNull(HomeWidgetActions.consume(intent,context.packageName))
    }
    @Test fun stalePauseCannotAffectNewOrAlreadyPausedRun() {
        val request=HomeWidgetRequest(HomeWidgetAction.PAUSE_FOCUS,task,run)
        val active=ExecutionRun(run,task,1,anchor=ExecutionMoment(1,1,"boot"),targetMinutes=5)
        assertEquals(ExecutionCommand(task,ExecutionAction.PAUSE),request.matchingCommand(ExecutionSnapshot(focus=active)))
        assertNull(request.matchingCommand(ExecutionSnapshot(focus=active.copy(id="another-run"))))
        assertNull(request.matchingCommand(ExecutionSnapshot(focus=active.copy(paused=true))))
        assertNull(request.copy(action=HomeWidgetAction.RESUME_FOCUS).matchingCommand(ExecutionSnapshot(focus=active.copy(paused=true,due=true))))
    }
    @Test fun realRemoteViewsButtonsDispatchCaptureAndBoundFocusRequests() {
        val request=HomeWidgetRequest(HomeWidgetAction.PAUSE_FOCUS,task,run)
        val model=HomeWidgetModel("11/1 · 今日重点","虚构桌面任务","下一步 · 写下第一句","正在专注","暂停专注",request)
        val view=HomeWidgetViews.createForSize(context,74,model,156,156).apply(context,FrameLayout(context))
        val app=shadowOf(context as Application)
        view.findViewById<android.view.View>(R.id.widget_body).performClick()
        assertEquals(request.copy(action=HomeWidgetAction.REVIEW_FOCUS),HomeWidgetActions.consume(app.nextStartedActivity,context.packageName))
        view.findViewById<android.view.View>(R.id.widget_capture).performClick()
        assertEquals(HomeWidgetRequest(HomeWidgetAction.CAPTURE),HomeWidgetActions.consume(app.nextStartedActivity,context.packageName))
        view.findViewById<android.view.View>(R.id.widget_focus).performClick()
        assertEquals(request,HomeWidgetActions.consume(app.nextStartedActivity,context.packageName))
    }
}
