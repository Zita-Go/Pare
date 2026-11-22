package com.step.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/** Windows 11 DWM caption colors; unsupported Windows versions keep their native frame. */
internal interface DesktopDwm:StdCallLibrary {
    fun DwmSetWindowAttribute(window:Pointer,attribute:Int,value:Pointer,size:Int):Int
}
internal fun applyWindowsFrameAppearance(window:Window,dark:Boolean) {
    if(!System.getProperty("os.name").startsWith("Windows",true) || !window.isDisplayable)return
    runCatching {
        val api=Native.load("dwmapi",DesktopDwm::class.java)
        val handle=Native.getComponentPointer(window)
        fun set(attribute:Int,value:Int) {Memory(4).use {it.setInt(0,value);api.DwmSetWindowAttribute(handle,attribute,it,4)}}
        set(20,if(dark)1 else 0)
        // COLORREF is 0x00BBGGRR; DWMWA_CAPTION_COLOR / DWMWA_TEXT_COLOR.
        set(35,if(dark)0x001D2117 else 0xFFFFFFFF.toInt())
        set(36,if(dark)0x00E8F0F3 else 0xFFFFFFFF.toInt())
    }
}
