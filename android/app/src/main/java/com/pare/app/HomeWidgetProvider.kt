package com.pare.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.util.SizeF
import android.view.View
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
    fun create(context:Context,id:Int,model:HomeWidgetModel):RemoteViews {
        if(Build.VERSION.SDK_INT>=31)return RemoteViews(linkedMapOf(
            SizeF(110f,110f) to createForSize(context,id,model,110,110),
            SizeF(110f,200f) to createForSize(context,id,model,110,200),
            SizeF(110f,260f) to createForSize(context,id,model,110,260),
            SizeF(140f,260f) to createForSize(context,id,model,140,260),
            SizeF(156f,156f) to createForSize(context,id,model,156,156),
            SizeF(280f,240f) to createForSize(context,id,model,280,240),
            SizeF(280f,300f) to createForSize(context,id,model,280,300)
        ))
        val options=AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
        val minWidth=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,156)
        val minHeight=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,156)
        val maxWidth=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,minWidth)
        val maxHeight=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,minHeight)
        return RemoteViews(createForSize(context,id,model,maxWidth,minHeight),createForSize(context,id,model,minWidth,maxHeight))
    }

    /** Also used for exact-size rendering acceptance; every responsive variant goes through here. */
    // Distinct IDs force already-placed widgets to reinflate after the Button→ImageButton change.
    fun createForSize(context:Context,id:Int,model:HomeWidgetModel,width:Int,height:Int):RemoteViews {
        val expanded=width>=280 && height>=240
        val tallCompact=!expanded && height>=260
        val short=height<156
        val largeText=context.resources.configuration.fontScale>1.35f
        return RemoteViews(context.packageName,if(expanded)R.layout.widget_card_expanded else if(tallCompact)R.layout.widget_card_tall else R.layout.widget_card_compact).apply {
            val roomy=expanded && height>=300 && !largeText
            setInt(R.id.widget_title,"setMaxLines",if(roomy || (!expanded && !short && (context.resources.configuration.fontScale<=1.1f || height>=200)))2 else 1)
            setInt(R.id.widget_status,"setMaxLines",if(roomy)2 else 1)
            setInt(R.id.widget_next_step,"setMaxLines",if(tallCompact || (expanded && (!largeText || roomy)))2 else 1)
            if(!expanded) {
                val density=context.resources.displayMetrics.density
                val horizontal=if(width>=132)10 else 6
                val vertical=if(tallCompact)10 else 6
                setViewPadding(android.R.id.background,(horizontal*density).toInt(),(vertical*density).toInt(),(horizontal*density).toInt(),(vertical*density).toInt())
                setViewVisibility(R.id.widget_heading,if(short)View.GONE else View.VISIBLE)
                setViewVisibility(R.id.widget_next_step,if(short && context.resources.configuration.fontScale>1.1f)View.GONE else View.VISIBLE)
                setViewVisibility(R.id.widget_status,if(tallCompact)View.VISIBLE else View.GONE)
            }
            setViewVisibility(R.id.widget_status,if(model.statusImportant && !short)View.VISIBLE else View.GONE)
            if(model.statusImportant) {
                setViewVisibility(R.id.widget_next_step,View.GONE)
                if(!expanded && !tallCompact && largeText)setViewVisibility(R.id.widget_heading,View.GONE)
            }
            setTextViewText(R.id.widget_heading,model.heading)
            setTextViewText(R.id.widget_title,if(model.statusImportant && short)model.status else model.title)
            setTextViewText(R.id.widget_next_step,model.nextStep)
            setTextViewText(R.id.widget_status,model.status)
            setTextViewText(R.id.widget_focus,if(expanded)model.actionLabel else when(model.request.action) {
                HomeWidgetAction.START_FOCUS->"开始";HomeWidgetAction.PAUSE_FOCUS->"暂停"
                HomeWidgetAction.RESUME_FOCUS->"继续";HomeWidgetAction.REVIEW_FOCUS->"查看"
                else->"打开"
            })
            val bodyRequest=if(model.request.runId!=null)model.request.copy(action=HomeWidgetAction.REVIEW_FOCUS) else HomeWidgetRequest(HomeWidgetAction.TODAY)
            val destination=if(bodyRequest.runId!=null)"打开这件事的推进记录" else "打开今日规划"
            setContentDescription(R.id.widget_body,listOfNotNull(model.heading,model.title,model.nextStep,model.status.takeIf {model.statusImportant}).joinToString("，")+"。$destination")
            setContentDescription(R.id.widget_capture,"快速收集想法")
            setContentDescription(R.id.widget_focus,"${model.actionLabel}：${model.title}"+if(model.statusImportant)"，${model.status}" else "")
            setOnClickPendingIntent(R.id.widget_body,HomeWidgetActions.pendingIntent(context,id,bodyRequest))
            setOnClickPendingIntent(R.id.widget_capture,HomeWidgetActions.pendingIntent(context,id,HomeWidgetRequest(HomeWidgetAction.CAPTURE)))
            setOnClickPendingIntent(R.id.widget_focus,HomeWidgetActions.pendingIntent(context,id,model.request))
        }
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
