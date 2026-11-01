package com.pare.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual RemoteViews at compact, narrow, short and expanded host sizes, without personal data. */
class HomeWidgetRenderingTest {
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
                val body=widget.findViewById<View>(R.id.widget_body)
                val bodyBounds=bounds(body)
                assertTrue("$label body touch height",body.height+1>=48*density)
                assertTrue("$label full task description",body.contentDescription.toString().contains(model.title))
                assertTrue("$label full next step accessible",body.contentDescription.toString().contains(model.nextStep))
                val buttons=listOf(R.id.widget_capture,R.id.widget_focus).map {id->
                    val button=widget.findViewById<TextView>(id);val rect=bounds(button)
                    assertTrue("$label clickable button",button.isClickable)
                    assertTrue("$label button touch height",button.height+1>=48*density)
                    assertTrue("$label button touch width ${button.width/density}",button.width+1>=48*density)
                    assertTrue("$label action inside widget",rect.left>=0 && rect.right<=width && rect.bottom<=height)
                    assertTrue("$label body must not cover action",bodyBounds.bottom<=rect.top)
                    assertTrue("$label complete button label",button.layout.getEllipsisCount(0)==0)
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
}
