package com.pare.app

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual RemoteViews at compact, narrow, short and expanded host sizes, without personal data. */
class HomeWidgetRenderingTest {
    @Test fun placedLegacyWidgetReinflatesCaptureIconAfterUpgrade() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(context.packageName=="com.pare.app.qa")
        val provider=ComponentName(context,HomeWidgetProvider::class.java)
        val info=requireNotNull(AppWidgetManager.getInstance(context).installedProviders.firstOrNull {it.provider==provider})
        val model=HomeWidgetModel("11/1 · 今日重点","虚构：升级后整理桌面","下一步 · 写下一条虚构观察","从最小下一步开始",
            "开始专注",HomeWidgetRequest(HomeWidgetAction.START_FOCUS,"99000000-0000-4000-8000-000000000001"))
        instrumentation.runOnMainSync {
            // A local native host reproduces an already-inflated old widget without binding a launcher widget.
            val host=AppWidgetHostView(context)
            host.setAppWidget(41,info)
            val legacy=RemoteViews(context.packageName,R.layout.home_widget_tall)
            host.updateAppWidget(legacy)
            val previousCapture=host.findViewById<View>(R.id.widget_capture)
            assertTrue("Legacy widget starts with the shipped text button",previousCapture is Button)
            val updated=HomeWidgetViews.createForSize(context,41,model,145,273)
            assertNotEquals("Changing the capture view type requires a new layout identity",legacy.layoutId,updated.layoutId)
            host.updateAppWidget(updated)
            val density=context.resources.displayMetrics.density
            val width=(145*density).toInt();val height=(273*density).toInt()
            host.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            host.layout(0,0,width,height)
            val capture=host.findViewById<View>(R.id.widget_capture)
            assertTrue("Upgrade must inflate the icon instead of reusing the cached text button",capture is ImageButton)
            assertNotSame("The native host must replace the old capture view",previousCapture,capture)
            assertEquals("快速收集想法",capture.contentDescription.toString())
            assertTrue("Upgraded capture remains clickable",capture.isClickable)
            assertTrue("Upgraded capture touch width",capture.width+1>=48*density)
            assertTrue("Upgraded capture touch height",capture.height+1>=48*density)
            assertEquals(model.title,host.findViewById<TextView>(R.id.widget_title).text.toString())
            val body=host.findViewById<View>(R.id.widget_body)
            assertTrue("Upgraded body retains the new task description",body.contentDescription.toString().contains(model.title))
            assertTrue("Upgraded body retains the next step",body.contentDescription.toString().contains(model.nextStep))
            assertTrue("Upgraded body remains clickable",body.isClickable)
            assertTrue("Upgraded focus action remains clickable",host.findViewById<View>(R.id.widget_focus).isClickable)
            assertEquals("开始",host.findViewById<TextView>(R.id.widget_focus).text.toString())
        }
    }

    @Test fun realRemoteViewsFitTwoByTwoAndResizeWithoutOverlappingActions() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val base=instrumentation.targetContext
        check(base.packageName=="com.pare.app.qa")
        val model=HomeWidgetModel("11/1 · 正在专注","整理演示用的读书笔记并留下一条观察","下一步 · 写出三个具体要点","正在专注 · 整理演示用的读书笔记","暂停专注",HomeWidgetRequest(HomeWidgetAction.PAUSE_FOCUS,"00000000-0000-4000-8000-000000000001","00000000-0000-4000-8000-000000000002"))
        for(dark in listOf(false,true)) for(font in listOf(1f,1.3f,1.6f)) for((widthDp,heightDp) in listOf(110 to 110,156 to 156,180 to 180,130 to 220,269 to 117,110 to 200,280 to 240,280 to 300,110 to 260,145 to 273,320 to 240)) {
            val config=Configuration(base.resources.configuration).apply {
                uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if(dark)Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                fontScale=font
            }
            val context=base.createConfigurationContext(config)
            instrumentation.runOnMainSync {
                val host=FrameLayout(context)
                val widget=HomeWidgetViews.createForSize(context,41,model,widthDp,heightDp).apply(context,host) as ViewGroup
                host.addView(widget)
                val density=context.resources.displayMetrics.density
                val width=(widthDp*density).toInt();val height=(heightDp*density).toInt()
                val label="$widthDp x $heightDp font=$font dark=$dark"
                host.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                host.layout(0,0,width,height)
                fun bounds(view:View)=Rect(0,0,view.width,view.height).also {widget.offsetDescendantRectToMyCoords(view,it)}
                assertEquals(model.title,widget.findViewById<TextView>(R.id.widget_title).text.toString())
                assertEquals(if(widthDp>=280 && heightDp>=240)"暂停专注" else "暂停",widget.findViewById<TextView>(R.id.widget_focus).text.toString())
                assertEquals("$label ordinary status is already represented in the heading",View.GONE,widget.findViewById<View>(R.id.widget_status).visibility)
                val body=widget.findViewById<View>(R.id.widget_body)
                val bodyBounds=bounds(body)
                assertTrue("$label body touch height",body.height+1>=48*density)
                assertTrue("$label full task description",body.contentDescription.toString().contains(model.title))
                assertTrue("$label full next step accessible",body.contentDescription.toString().contains(model.nextStep))
                assertEquals("$label capture accessible purpose","快速收集想法",widget.findViewById<View>(R.id.widget_capture).contentDescription.toString())
                val buttons=listOf(R.id.widget_capture,R.id.widget_focus).map {id->
                    val button=widget.findViewById<View>(id);val rect=bounds(button)
                    assertTrue("$label clickable button",button.isClickable)
                    assertTrue("$label button touch height",button.height+1>=48*density)
                    assertTrue("$label button touch width ${button.width/density}",button.width+1>=48*density)
                    assertTrue("$label action inside widget",rect.left>=0 && rect.right<=width && rect.bottom<=height)
                    assertTrue("$label body must not cover action",bodyBounds.bottom<=rect.top)
                    if(id==R.id.widget_focus)assertTrue("$label complete focus label",(button as TextView).layout.getEllipsisCount(0)==0)
                    rect
                }
                assertTrue("$label disjoint actions",buttons[0].right<=buttons[1].left)
                var previousBottom=bodyBounds.top
                for(id in listOf(R.id.widget_heading,R.id.widget_title,R.id.widget_next_step,R.id.widget_status)) {
                    val text=widget.findViewById<TextView>(id)
                    if(text.visibility==View.VISIBLE) {
                        val rect=bounds(text)
                        assertTrue("$label text clipped at body bottom: $id",rect.bottom<=bodyBounds.bottom)
                        assertTrue("$label text overlaps previous line",rect.top>=previousBottom)
                        assertTrue("$label visible text height",text.height>0)
                        val lastVisibleLine=minOf(text.layout.lineCount,text.maxLines)-1
                        assertTrue("$label visible lines fit text view $id: lineBottom=${text.layout.getLineBottom(lastVisibleLine)} available=${text.height-text.compoundPaddingTop-text.compoundPaddingBottom}",text.layout.getLineBottom(lastVisibleLine)<=text.height-text.compoundPaddingTop-text.compoundPaddingBottom+1)
                        previousBottom=rect.bottom
                    }
                }
                if((widthDp==180 && heightDp==180 && font==1f) || (widthDp==110 && heightDp==110 && font==1.3f) || (widthDp==320 && heightDp==240 && font==1.3f)) {
                    val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);host.draw(Canvas(bitmap))
                    InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let {path ->
                        val output=File(path).apply {mkdirs()}
                        File(output,"widget-${widthDp}x${heightDp}-${if(dark)"dark" else "light"}.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                    };bitmap.recycle()
                }
            }
        }
    }

    @Test fun placedSizeKeepsFictionalTitleBalancedAndActualNextStepProminent() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val base=instrumentation.targetContext
        check(base.packageName=="com.pare.app.qa")
        // This title left a single 察 on the last line in the real 2x2 launcher screenshot.
        val model=HomeWidgetModel("11/1 · 今日重点","虚构：整理桌面观察","下一步 · 写下一条虚构观察","从最小下一步开始",
            "开始专注",HomeWidgetRequest(HomeWidgetAction.START_FOCUS,"99000000-0000-4000-8000-000000000001"))
        for(dark in listOf(false,true)) for(font in listOf(1f,1.3f,1.6f)) {
            val config=Configuration(base.resources.configuration).apply {
                uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if(dark)Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                fontScale=font
            }
            val context=base.createConfigurationContext(config)
            instrumentation.runOnMainSync {
                val host=FrameLayout(context)
                val widget=HomeWidgetViews.createForSize(context,41,model,145,273).apply(context,host) as ViewGroup
                host.addView(widget)
                val density=context.resources.displayMetrics.density
                val width=(145*density).toInt();val height=(273*density).toInt()
                val label="145 x 273 font=$font dark=$dark"
                host.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                host.layout(0,0,width,height)
                val title=widget.findViewById<TextView>(R.id.widget_title)
                assertEquals(model.title,title.text.toString())
                val layout=title.layout
                assertTrue("$label title must occupy at most two lines",layout.lineCount in 1..2)
                val lastLine=minOf(layout.lineCount,title.maxLines)-1
                assertTrue("$label title lines must remain visible",layout.getLineBottom(lastLine)<=title.height-title.compoundPaddingTop-title.compoundPaddingBottom+1)
                val ellipsized=(0..lastLine).any {layout.getEllipsisCount(it)>0}
                if(lastLine>0 && !ellipsized) {
                    val lastText=title.text.subSequence(layout.getLineStart(lastLine),layout.getLineVisibleEnd(lastLine)).toString().trim()
                    val singleCjk=lastText.codePointCount(0,lastText.length)==1 && Character.UnicodeScript.of(lastText.codePointAt(0))==Character.UnicodeScript.HAN
                    assertFalse("$label title leaves an orphan final character: $lastText",singleCjk)
                }
                val next=widget.findViewById<TextView>(R.id.widget_next_step)
                assertEquals("$label actual next step remains visible",View.VISIBLE,next.visibility)
                assertEquals(model.nextStep,next.text.toString())
                assertEquals("$label generic encouragement must not duplicate the actual next step",View.GONE,widget.findViewById<View>(R.id.widget_status).visibility)
                val body=widget.findViewById<View>(R.id.widget_body)
                assertTrue("$label complete title remains accessible",body.contentDescription.toString().contains(model.title))
                assertTrue("$label complete next step remains accessible",body.contentDescription.toString().contains(model.nextStep))
                val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                try {
                    host.draw(Canvas(bitmap))
                    InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let {path ->
                        val output=File(path).apply {mkdirs()}
                        File(output,"widget-placed-145x273-font$font-${if(dark)"dark" else "light"}.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
                    }
                } finally {bitmap.recycle()}
            }
        }
    }

    @Test fun tallWidgetKeepsActionableStatusWarningsVisible() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(context.packageName=="com.pare.app.qa")
        val base=HomeWidgetModel("11/1 · 状态待检查","虚构：整理桌面观察","下一步 · 写下一条虚构观察","专注状态暂不可用，请打开 Pare 检查",
            "打开 Pare",HomeWidgetRequest(HomeWidgetAction.TODAY),statusImportant=true)
        val warnings=listOf(base,
            base.copy(heading="11/1 · 待处理冲突",status="这件事有同步冲突，请先查看"),
            base.copy(heading="11/1 · 专注记录待保存",status="专注记录待保存 · 虚构：整理桌面观察",actionLabel="查看专注",
                request=HomeWidgetRequest(HomeWidgetAction.REVIEW_FOCUS,"99000000-0000-4000-8000-000000000001","99000000-0000-4000-8000-000000000002")))
        instrumentation.runOnMainSync {
            for(model in warnings) {
                val host=FrameLayout(context)
                val widget=HomeWidgetViews.createForSize(context,41,model,145,273).apply(context,host)
                val status=widget.findViewById<TextView>(R.id.widget_status)
                assertEquals("Actionable warning must remain visible: ${model.status}",View.VISIBLE,status.visibility)
                assertEquals(model.status,status.text.toString())
                assertTrue("Full warning remains accessible",widget.findViewById<View>(R.id.widget_body).contentDescription.toString().contains(model.status))
            }
        }
    }
}
