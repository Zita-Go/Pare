package com.step.desktop

import com.pare.app.wire
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.platform.win32.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Serializable data class DesktopSystemSettings(val startAtLogin:Boolean=false,val systemNotifications:Boolean=false,val afterExitReminders:Boolean=false,val attentionEnabled:Boolean=false,val attentionSnoozedUntil:Long=0,val autoCheckUpdates:Boolean=false,val attentionBrowserEnabled:Boolean=false)
internal data class DesktopAttentionSample(val idleMillis:Long,val foreground:Long,val browserToken:String?=null)
internal data class DesktopNotice(val title:String,val body:String,val token:String,val kind:String)
internal data class DesktopScheduledNotice(val token:String,val atMillis:Long)
internal fun desktopPowerShellProcess(script:Path,input:Path):ProcessBuilder {
    val powershell=Path.of(System.getenv("SystemRoot") ?: "C:\\Windows","System32","WindowsPowerShell","v1.0","powershell.exe")
    // Process-only policy for our locally extracted script. Group Policy still takes precedence;
    // this does not change CurrentUser/LocalMachine policy or permit unsigned downloaded scripts.
    return ProcessBuilder(powershell.toString(),"-NoProfile","-NonInteractive","-ExecutionPolicy","RemoteSigned","-File",script.toString(),"-InputFile",input.toString()).apply {
        // pwsh7 only cleans module paths when it launches powershell.exe directly. A Java
        // intermediary otherwise forwards incompatible PS7 modules. Our helper needs only
        // the Windows PowerShell built-ins; modify this child environment, never user/machine state.
        val childEnvironment=environment()
        childEnvironment.keys.filter {it.equals("PSModulePath",ignoreCase=true)}.forEach(childEnvironment::remove)
        childEnvironment["PSModulePath"]=powershell.parent.resolve("Modules").toString()
    }
}

/** Windows API adapter. Operations touch only this user's profile-specific registrations. */
internal class DesktopWindows(private val home:Path) {
    private val profile=DesktopBackup.fingerprint(home.toAbsolutePath().normalize().toString().toByteArray()).take(12)
    val scheme="step-$profile"
    private val appId="Step.Desktop.$profile"
    private val executable:Path?=System.getProperty("step.executable")?.let(Path::of)
        ?: Path.of(System.getProperty("java.home")).parent?.resolve("Step.exe")?.takeIf {Files.isRegularFile(it)}
    val supported=System.getProperty("os.name").startsWith("Windows",true) && executable?.let {Files.isRegularFile(it)}==true
    private val runKey="Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private val runName="Step-$profile"
    private val browserIdentity=DesktopBrowserIdentity()
    private fun requireSupported()=check(supported) {"此功能需要已安装或可携带的 Windows Step 程序"}
    fun configure(settings:DesktopSystemSettings) {
        requireSupported()
        require(!settings.afterExitReminders || settings.systemNotifications) {"退出后提醒需要开启系统通知"}
        require(!settings.attentionBrowserEnabled || settings.attentionEnabled) {"浏览器增强需要先开启本机注意力提示"}
        val command="\"$executable\" --data-dir \"${home.toAbsolutePath()}\" --start-hidden"
        require(command.length<=260 && !command.contains('\n')) {"程序或资料路径过长，不能添加登录启动项"}
        Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER,runKey)
        if(settings.startAtLogin)Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER,runKey,runName,command)
        else if(Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER,runKey,runName))Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER,runKey,runName)
        invoke("configure",buildJsonObject {put("notifications",settings.systemNotifications);put("scheduled",settings.afterExitReminders)})
    }
    fun show(notice:DesktopNotice) {
        requireSupported()
        invoke("show",buildJsonObject {put("title",notice.title.take(200));put("body",notice.body.take(1000));put("token",notice.token);put("kind",notice.kind);put("tag",DesktopBackup.fingerprint(notice.token.toByteArray()).take(16))})
    }
    fun schedule(events:List<DesktopScheduledNotice>) {
        requireSupported()
        invoke("schedule",buildJsonObject {put("events",JsonArray(events.take(100).map {event->buildJsonObject {
            put("id",DesktopBackup.fingerprint(event.token.toByteArray()).take(24));put("token",event.token);put("atMillis",event.atMillis)
        }}))})
    }
    fun clearBrowserIdentity()=browserIdentity.reset()
    fun sample(browserTitles:Boolean=false):DesktopAttentionSample? {
        if(!supported)return null
        val input=WinUser.LASTINPUTINFO()
        if(!User32.INSTANCE.GetLastInputInfo(input))return null
        val idle=(Kernel32.INSTANCE.GetTickCount().toLong()-input.dwTime.toLong()) and 0xffffffffL
        val window=User32.INSTANCE.GetForegroundWindow()
        val token=if(browserTitles && window!=null)browserToken(window) else null
        return DesktopAttentionSample(idle,window?.pointer?.let(Pointer::nativeValue) ?: 0,token)
    }
    private fun browserToken(window:WinDef.HWND):String? {
        val processId=IntByReference()
        if(User32.INSTANCE.GetWindowThreadProcessId(window,processId)==0 || processId.value==0)return null
        val process=Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION,false,processId.value) ?: return null
        val path=CharArray(32_768)
        try {
            val length=IntByReference(path.size)
            if(!Kernel32.INSTANCE.QueryFullProcessImageName(process,0,path,length))return null
            val executable=String(path,0,length.value).substringAfterLast('\\')
            if(!browserIdentity.supports(executable))return null // Never request a non-browser title.
            val title=CharArray(4096)
            try {return browserIdentity.token(executable,title,User32.INSTANCE.GetWindowText(window,title,title.size))}
            finally {title.fill('\u0000')}
        } finally {path.fill('\u0000');Kernel32.INSTANCE.CloseHandle(process)}
    }
    fun inspect():JsonObject {
        requireSupported()
        val result=wire.parseToJsonElement(invoke("inspect",buildJsonObject {})).jsonObject
        val registered=Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER,runKey,runName)
        return JsonObject(result+("startAtLogin" to JsonPrimitive(registered)))
    }
    private fun invoke(operation:String,payload:JsonObject):String {
        val directory=home.resolve("windows-system");Files.createDirectories(directory)
        val script=directory.resolve("step-windows.ps1")
        val bytes=DesktopWindows::class.java.getResourceAsStream("/windows/step-windows.ps1")?.use {it.readBytes()} ?: error("Windows 系统组件缺失，请重新安装")
        if(!Files.exists(script) || !Files.readAllBytes(script).contentEquals(bytes))DesktopAtomicFile(script).write(bytes,retainBackup=false)
        val input=Files.createTempFile(directory,"request-",".json")
        val output=Files.createTempFile(directory,"result-",".txt")
        val request=JsonObject(payload+mapOf("operation" to JsonPrimitive(operation),"home" to JsonPrimitive(home.toAbsolutePath().toString()),"executable" to JsonPrimitive(executable.toString()),"appId" to JsonPrimitive(appId),"scheme" to JsonPrimitive(scheme),"profile" to JsonPrimitive(profile)))
        Files.writeString(input,request.toString())
        var process:Process?=null
        try {
            process=desktopPowerShellProcess(script,input).redirectErrorStream(true).redirectOutput(output.toFile()).start()
            check(process.waitFor(30,TimeUnit.SECONDS)) {"Windows 系统操作超时，请重试"}
            check(process.exitValue()==0) {
                val detail=if(operation=="show")"" else Files.newInputStream(output).use {it.readNBytes(3_000)}.toString(Charsets.UTF_8).trim()
                "Windows 系统操作失败（$operation，${process.exitValue()}）；请检查通知权限、脚本策略或安装位置。$detail"
            }
            require(Files.size(output)<=64*1024)
            return Files.readString(output).trim().removePrefix("\uFEFF")
        } finally {if(process?.isAlive==true)process.destroyForcibly();Files.deleteIfExists(input);Files.deleteIfExists(output)}
    }
}
