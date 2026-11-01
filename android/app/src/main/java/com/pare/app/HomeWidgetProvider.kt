package com.pare.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import android.widget.RemoteViews
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class HomeWidgetProvider:AppWidgetProvider() {
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray)=HomeWidgetUpdates.refresh(context)
    override fun onAppWidgetOptionsChanged(context:Context,manager:AppWidgetManager,id:Int,options:Bundle)=HomeWidgetUpdates.refresh(context)
    override fun onDisabled(context:Context)=HomeWidgetUpdates.cancel(context)
    override fun onReceive(context:Context,intent:Intent) {
        super.onReceive(context,intent)
        if(intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_USER_UNLOCKED,Intent.ACTION_DATE_CHANGED))
            HomeWidgetUpdates.refresh(context)
    }
    companion object {
        /** Called only from the user's explicit Add widget action. Launcher still owns confirmation. */
        fun requestPin(context:Context):Boolean {
            val manager=AppWidgetManager.getInstance(context)
            return manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(ComponentName(context,HomeWidgetProvider::class.java),null,null)
        }
    }
}

object HomeWidgetViews {
    fun create(context:Context,id:Int,model:HomeWidgetModel)=RemoteViews(context.packageName,R.layout.home_widget).apply {
        val height=AppWidgetManager.getInstance(context).getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,240)
        val compact=height<260
        setInt(R.id.widget_title,"setMaxLines",if(compact)1 else 2)
        setInt(R.id.widget_status,"setMaxLines",if(compact)1 else 2)
        setTextViewText(R.id.widget_heading,model.heading)
        setTextViewText(R.id.widget_title,model.title)
        setTextViewText(R.id.widget_next_step,model.nextStep)
        setTextViewText(R.id.widget_status,model.status)
        setTextViewText(R.id.widget_focus,model.actionLabel)
        setContentDescription(R.id.widget_body,"${model.heading}，${model.title}，${model.nextStep}，${model.status}。打开今日规划")
        setContentDescription(R.id.widget_focus,"${model.actionLabel}，${model.status}")
        setOnClickPendingIntent(R.id.widget_body,HomeWidgetActions.pendingIntent(context,id,HomeWidgetRequest(HomeWidgetAction.TODAY)))
        setOnClickPendingIntent(R.id.widget_capture,HomeWidgetActions.pendingIntent(context,id,HomeWidgetRequest(HomeWidgetAction.CAPTURE)))
        setOnClickPendingIntent(R.id.widget_focus,HomeWidgetActions.pendingIntent(context,id,model.request))
    }
}

/** Event-driven local refresh, with one delayed boundary update; no sync, credentials, or AI. */
object HomeWidgetUpdates {
    private const val REFRESH="pare.widget.refresh"
    private const val BOUNDARY="pare.widget.boundary"
    fun ids(context:Context):IntArray=AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context,HomeWidgetProvider::class.java))
    fun refresh(context:Context) {
        // A launcher/work-scheduler failure must never break a task save or execution command.
        runCatching {
            if(ids(context).isNotEmpty())WorkManager.getInstance(context).enqueueUniqueWork(REFRESH,ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<HomeWidgetWorker>().build())
        }
    }
    internal fun schedule(context:Context,atMillis:Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(BOUNDARY,ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HomeWidgetBoundaryWorker>()
                .setInitialDelay((atMillis-System.currentTimeMillis()).coerceAtLeast(1_000),TimeUnit.MILLISECONDS).build())
    }
    fun cancel(context:Context) {
        val manager=WorkManager.getInstance(context)
        manager.cancelUniqueWork(REFRESH);manager.cancelUniqueWork(BOUNDARY)
    }
}

/** The boundary worker delegates so it never replaces/cancels itself while scheduling tomorrow. */
class HomeWidgetBoundaryWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {HomeWidgetUpdates.refresh(applicationContext);return Result.success()}
}

class HomeWidgetWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val context=applicationContext
        val ids=HomeWidgetUpdates.ids(context)
        if(ids.isEmpty())return@withContext Result.success()
        var nextRefresh:Long?=null
        val model=if(context.getSystemService(UserManager::class.java)?.isUserUnlocked==false) {
            HomeWidgetProjection.unavailable(locked=true)
        } else runCatching {
            // Never initialize SyncRuntime here: that owner is allowed to initiate account sync.
            val state=PareRepository(context).use {it.read()}
            val execution=runCatching {ExecutionFilePersistence(context).load()}
            val now=AndroidExecutionClock(context).now()
            nextRefresh=HomeWidgetProjection.nextRefreshAt(state,execution.getOrNull(),now)
            HomeWidgetProjection.project(state,execution.getOrNull(),now,executionUnavailable=execution.isFailure)
        }.getOrElse {HomeWidgetProjection.unavailable()}
        val manager=AppWidgetManager.getInstance(context)
        ids.forEach {manager.updateAppWidget(it,HomeWidgetViews.create(context,it,model))}
        nextRefresh?.let {HomeWidgetUpdates.schedule(context,it)}
        Result.success()
    }
}
