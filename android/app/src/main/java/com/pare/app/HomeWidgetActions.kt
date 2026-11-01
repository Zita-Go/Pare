package com.pare.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.UUID

enum class HomeWidgetAction { TODAY, CAPTURE, START_FOCUS, PAUSE_FOCUS, RESUME_FOCUS, REVIEW_FOCUS }
data class HomeWidgetRequest(val action:HomeWidgetAction,val taskId:String?=null,val runId:String?=null) {
    /** The runtime calls this under the execution controller's monitor immediately before mutation. */
    fun matchingCommand(snapshot:ExecutionSnapshot):ExecutionCommand? {
        val run=snapshot.focus ?: return null
        if(run.id!=runId || run.taskId!=taskId || run.due || run.pendingFinish!=null)return null
        return when {
            action==HomeWidgetAction.PAUSE_FOCUS && !run.paused -> ExecutionCommand(run.taskId,ExecutionAction.PAUSE)
            action==HomeWidgetAction.RESUME_FOCUS && run.paused -> ExecutionCommand(run.taskId,ExecutionAction.RESUME)
            else -> null
        }
    }
}

/** Explicit immutable activity intents. No exported receiver accepts execution commands. */
object HomeWidgetActions {
    const val ACTION_EXTRA="pare.widget.action"
    const val TASK_EXTRA="pare.widget.task"
    const val RUN_EXTRA="pare.widget.run"
    fun intent(context:Context,widgetId:Int,request:HomeWidgetRequest)=Intent(context,MainActivity::class.java).apply {
        action="${context.packageName}.HOME_WIDGET"
        data=Uri.Builder().scheme("pare-widget").authority(context.packageName)
            .appendPath(widgetId.toString()).appendPath(request.action.name)
            .appendPath(request.taskId.orEmpty()).appendPath(request.runId.orEmpty()).build()
        flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(ACTION_EXTRA,request.action.name)
        request.taskId?.let {putExtra(TASK_EXTRA,it)}
        request.runId?.let {putExtra(RUN_EXTRA,it)}
    }
    fun pendingIntent(context:Context,widgetId:Int,request:HomeWidgetRequest):PendingIntent=
        PendingIntent.getActivity(context,widgetId,intent(context,widgetId,request),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun consume(intent:Intent?,packageName:String):HomeWidgetRequest? {
        intent ?: return null
        if(intent.action!="$packageName.HOME_WIDGET")return null
        return try {
            val action=HomeWidgetAction.entries.firstOrNull {it.name==intent.getStringExtra(ACTION_EXTRA)} ?: return null
            val task=intent.getStringExtra(TASK_EXTRA)
            val run=intent.getStringExtra(RUN_EXTRA)
            fun validId(value:String?)=value!=null && runCatching {UUID.fromString(value).toString()==value}.getOrDefault(false)
            val uri=intent.data ?: return null
            if(uri.scheme!="pare-widget" || uri.authority!=packageName || uri.pathSegments.firstOrNull()?.toIntOrNull()==null)return null
            if(uri.pathSegments.getOrNull(1)!=action.name)return null
            when(action) {
                HomeWidgetAction.TODAY,HomeWidgetAction.CAPTURE -> if(task!=null || run!=null)return null
                HomeWidgetAction.START_FOCUS -> if(!validId(task) || run!=null)return null
                else -> if(!validId(task) || !validId(run))return null
            }
            if(uri.pathSegments.getOrNull(2).orEmpty()!=task.orEmpty() || uri.pathSegments.getOrNull(3).orEmpty()!=run.orEmpty())return null
            HomeWidgetRequest(action,task,run)
        } catch(_:RuntimeException) {null}
        finally {
            // Consume the command, but retain the activity's launch identity for lifecycle/task tracking.
            intent.removeExtra(ACTION_EXTRA);intent.removeExtra(TASK_EXTRA);intent.removeExtra(RUN_EXTRA)
        }
    }
}
