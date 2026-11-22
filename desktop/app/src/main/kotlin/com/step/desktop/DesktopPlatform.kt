package com.step.desktop

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.SwingUtilities
import kotlinx.coroutines.flow.MutableStateFlow

internal data class ShortcutSpec(val modifiers:Int,val key:Int) {
    companion object {
        fun parse(value:String):ShortcutSpec {
            val parts=value.uppercase().split('+').map {it.trim()}
            require(parts.size>=2 && parts.distinct().size==parts.size) {"例如 Ctrl+Shift+Space"}
            var mask=0
            for(part in parts.dropLast(1)) mask=mask or when(part) {
                "CTRL"->2;"ALT"->1;"SHIFT"->4;else->error("修饰键支持 Ctrl、Alt、Shift")
            }
            require(mask and 3 != 0) {"快捷键至少包含 Ctrl 或 Alt"}
            val key=parts.last()
            val code=when {
                key=="SPACE"->0x20
                key.length==1 && key[0] in 'A'..'Z'->key[0].code
                key.matches(Regex("F([1-9]|1[0-2])"))->0x70+key.drop(1).toInt()-1
                else->error("支持字母、Space 或 F1–F12")
            }
            return ShortcutSpec(mask,code)
        }
    }
}

internal class DesktopPlatform(private val home:Path,private val onCapture:()->Unit):AutoCloseable {
    private val file=home.resolve("window.properties")
    private val props=Properties().apply {if(Files.exists(file))Files.newInputStream(file).use {load(it)}}
    val shortcut=MutableStateFlow(props.getProperty("shortcut","Ctrl+Shift+Space"))
    val keepInTray=MutableStateFlow(props.getProperty("keepInTray","true").toBoolean())
    val status=MutableStateFlow<String?>(null)
    private val pending=ConcurrentLinkedQueue<String>()
    @Volatile private var running=true
    private val worker:Thread?
    init {
        if(System.getProperty("os.name").startsWith("Windows",true)) {
            pending.add(shortcut.value)
            worker=Thread({loop()},"Step-global-shortcut").apply {isDaemon=true;start()}
        } else {worker=null;status.value="全局快捷键仅在 Windows 安装包启用"}
    }
    fun changeShortcut(value:String) {
        try {ShortcutSpec.parse(value);if(worker==null)error("此环境不支持 Windows 全局快捷键");pending.add(value.trim())}
        catch(e:Exception) {status.value=e.message}
    }
    fun changeTray(value:Boolean) {keepInTray.value=value;persist()}
    @Synchronized private fun persist() {
        props.setProperty("shortcut",shortcut.value);props.setProperty("keepInTray",keepInTray.value.toString())
        val temp=home.resolve("window.properties.tmp")
        try {
            Files.newOutputStream(temp).use {props.store(it,"Step desktop preferences")}
            Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
        } catch(_:Exception) {status.value="桌面设置未能保存，请重试"}
    }
    private fun loop() {
        var registered:Int?=null;var nextId=1
        try {
            val user=User32.INSTANCE;val message=WinUser.MSG()
            while(running) {
                val request=pending.poll()
                if(request!=null) {
                    val spec=ShortcutSpec.parse(request);val id=nextId++
                    if(user.RegisterHotKey(null,id,spec.modifiers or 0x4000,spec.key)) {
                        registered?.let {user.UnregisterHotKey(null,it)};registered=id
                        shortcut.value=request;status.value=null;persist()
                    } else status.value="快捷键被占用，原快捷键保持不变；请换一个组合"
                }
                while(user.PeekMessage(message,null,0,0,1)) {
                    if(message.message==0x0312 && message.wParam.toInt()==registered)SwingUtilities.invokeLater(onCapture)
                }
                Thread.sleep(40)
            }
        } catch(_:Throwable) {status.value="全局快捷键暂不可用，请使用托盘中的随手记"}
        finally {registered?.let {runCatching {User32.INSTANCE.UnregisterHotKey(null,it)}}}
    }
    override fun close() {running=false;worker?.join(1000)}
}
