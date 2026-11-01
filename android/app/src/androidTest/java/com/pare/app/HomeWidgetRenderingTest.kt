package com.pare.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Inflates the actual RemoteViews layout; never opens a real library or account. */
class HomeWidgetRenderingTest {
    @Test fun realRemoteViewsFitLightDarkAndLargeText() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val base=instrumentation.targetContext
        check(base.packageName=="com.pare.app.qa")
        val model=HomeWidgetModel("11/1 · 今日重点","整理演示用的读书笔记","下一步 · 写出三个要点","正在专注 · 整理演示用的读书笔记","暂停专注",HomeWidgetRequest(HomeWidgetAction.PAUSE_FOCUS,"00000000-0000-4000-8000-000000000001","00000000-0000-4000-8000-000000000002"))
        for(dark in listOf(false,true)) {
            val config=Configuration(base.resources.configuration).apply {
                uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if(dark)Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                fontScale=1.3f
            }
            val context=base.createConfigurationContext(config)
            instrumentation.runOnMainSync {
                val host=FrameLayout(context)
                val widget=HomeWidgetViews.create(context,41,model).apply(context,host)
                host.addView(widget)
                val width=(320*context.resources.displayMetrics.density).toInt()
                val height=(240*context.resources.displayMetrics.density).toInt()
                host.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                host.layout(0,0,width,height)
                assertEquals(model.title,widget.findViewById<TextView>(R.id.widget_title).text.toString())
                assertEquals("暂停专注",widget.findViewById<TextView>(R.id.widget_focus).text.toString())
                val body=widget.findViewById<View>(R.id.widget_body)
                assertTrue(widget.findViewById<View>(R.id.widget_status).bottom<=body.height)
                for(id in listOf(R.id.widget_capture,R.id.widget_focus)) {
                    val button=widget.findViewById<View>(id)
                    assertTrue(button.isClickable)
                    assertTrue(button.height>=48*context.resources.displayMetrics.density)
                    assertTrue(button.bottom<=widget.height)
                }
                val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                host.draw(Canvas(bitmap))
                InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let {path ->
                    val output=File(path).apply {mkdirs()}
                    File(output,"home-widget-${if(dark)"dark" else "light"}.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                }
                bitmap.recycle()
            }
        }
    }
}
