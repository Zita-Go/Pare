package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import androidx.compose.ui.res.painterResource
import com.pare.app.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.awt.*
import java.awt.event.ActionListener
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

private fun configuration():DesktopConfig {
    val resource=object {}.javaClass.getResourceAsStream("/step-sync-config.json") ?: return DesktopConfig()
    val obj=resource.use {wire.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject}
    return DesktopConfig(obj["api_url"]?.jsonPrimitive?.content.orEmpty(),obj["publishable_key"]?.jsonPrimitive?.content.orEmpty())
}
private fun home(args:Array<String>):Path {
    val index=args.indexOf("--data-dir")
    if(index>=0) {require(index+1<args.size);return Path.of(args[index+1])}
    val root=System.getenv("APPDATA") ?: Path.of(System.getProperty("user.home"),".local","share").toString()
    return Path.of(root,"Step")
}
fun main(args:Array<String>) {
    val path=home(args)
    if("--self-test" in args) {selfTest(path);return}
    application {
        var visible by remember {mutableStateOf(true)}
        var capture by remember {mutableStateOf(false)}
        var desktopOptions by remember {mutableStateOf(false)}
        var quitting by remember {mutableStateOf(false)}
        var confirmExit by remember {mutableStateOf(false)}
        val darkAppearance by DesktopAppearance.dark.collectAsState()
        val notices=remember {mutableStateListOf<Pair<String,String>>()}
        var initializationError by remember {mutableStateOf<String?>(null)}
        val uiScope=rememberCoroutineScope()
        val trayIcon=remember {mutableStateOf<TrayIcon?>(null)}
        val runtime=remember {runCatching {DesktopRuntime(path,configuration()) {title,body ->
            SwingUtilities.invokeLater {notices.add(title to body);trayIcon.value?.displayMessage(title,body,TrayIcon.MessageType.INFO)}
        }}.getOrElse {initializationError=it.message ?: "本机资料暂不可用";null}}
        val platform=remember {runtime?.let {DesktopPlatform(path) {capture=!capture}}}
        fun finishQuit() {
            if(quitting)return
            quitting=true
            uiScope.launch {
                withContext(Dispatchers.IO) {platform?.close();runtime?.close()}
                exitApplication()
            }
        }
        fun quit() {
            if(DesktopDraftStatus.hasUnsavedEdits.value)confirmExit=true else finishQuit()
        }
        val requestClose:()->Unit={
            if(platform?.keepInTray?.value==true && trayIcon.value!=null)visible=false else quit()
        }
        LaunchedEffect(runtime) {runtime?.let {try {it.initialize()}catch(e:Exception){initializationError=e.message ?: "资料读取失败，原文件已保留"}}}
        DisposableEffect(runtime) {
            var icon:TrayIcon?=null
            if(SystemTray.isSupported()) {
                val image=ImageIO.read(object {}.javaClass.getResource("/step.png"))
                val menu=PopupMenu()
                fun entry(title:String,action:()->Unit) {menu.add(MenuItem(title).apply {addActionListener(ActionListener {action()})})}
                entry("打开 Step") {visible=true};entry("随手记") {capture=true};entry("桌面选项") {visible=true;desktopOptions=true}
                menu.addSeparator();entry("退出 Step") {quit()}
                icon=TrayIcon(image,"Step",menu).apply {isImageAutoSize=true;addActionListener {visible=true}}
                runCatching {SystemTray.getSystemTray().add(icon);trayIcon.value=icon}.onFailure {platform?.status?.value="系统托盘不可用，请保持窗口以接收提醒"}
            }
            onDispose {icon?.let {runCatching {SystemTray.getSystemTray().remove(it)}}}
        }
        Window(visible=visible,onCloseRequest=requestClose,title="Step",icon=painterResource("step.png"),state=rememberWindowState(width=1160.dp,height=780.dp)) {
            window.minimumSize=Dimension(960,680)
            LaunchedEffect(window,darkAppearance,visible) {applyWindowsFrameAppearance(window,darkAppearance)}
            if(runtime!=null && initializationError==null)Box {
                DesktopUi(runtime,{capture=true},requestClose,{desktopOptions=true})
                notices.firstOrNull()?.let {notice -> DesktopTheme {
                    Surface(modifier=Modifier.align(Alignment.BottomEnd).padding(20.dp).widthIn(max=460.dp),shadowElevation=8.dp,shape=MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(notice.first,style=MaterialTheme.typography.titleMedium);Text(notice.second)
                            TextButton(onClick={notices.removeAt(0)}) {Text(if(notices.size>1) "下一条（${notices.size-1}）" else "知道了")}
                        }
                    }
                }}
            }
            else DesktopTheme {Column(Modifier.fillMaxSize().padding(40.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("Step 暂时无法打开资料",style=MaterialTheme.typography.headlineMedium)
                Text(initializationError ?: "正在准备")
                Text("原有文件不会被清空。若另一个 Step 正在运行，请使用已有窗口。")
                Button(onClick={quit()},shape=MaterialTheme.shapes.small) {Text("关闭")}
            }}
        }
        if(runtime!=null) Window(visible=capture,onCloseRequest={capture=false},title="Step · 随手记",alwaysOnTop=true,resizable=false,state=rememberWindowState(width=460.dp,height=400.dp)) {
            LaunchedEffect(window,darkAppearance,capture) {applyWindowsFrameAppearance(window,darkAppearance)}
            QuickCaptureUi(runtime,onClose={capture=false},visible=capture)
        }
        if(confirmExit) DialogWindow(onCloseRequest={confirmExit=false},title="退出 Step？",resizable=false,state=rememberDialogState(width=420.dp,height=220.dp)) {
            DesktopTheme {Surface {Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Text("还有未保存的编辑",style=MaterialTheme.typography.titleLarge)
                Text("退出会丢弃这些草稿。已保存的任务会保留，正在进行的专注会暂停。")
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick={confirmExit=false;visible=true},shape=MaterialTheme.shapes.small) {Text("返回保存")}
                    Button(onClick={confirmExit=false;finishQuit()},shape=MaterialTheme.shapes.small) {Text("放弃草稿并退出")}
                }
            }}}
        }
        if(desktopOptions && platform!=null) DialogWindow(onCloseRequest={desktopOptions=false},title="Step · 桌面选项",resizable=false,state=rememberDialogState(width=470.dp,height=460.dp)) {
            LaunchedEffect(window,darkAppearance) {applyWindowsFrameAppearance(window,darkAppearance)}
            val current by platform.shortcut.collectAsState();val tray by platform.keepInTray.collectAsState();val status by platform.status.collectAsState()
            var value by remember(current) {mutableStateOf(current)}
            DesktopTheme {Surface {Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text("外观与桌面",style=MaterialTheme.typography.titleLarge)
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("外观",style=MaterialTheme.typography.bodyMedium)
                    FilterChip(darkAppearance,{platform.changeAppearance(true)},label={Text("深色")},shape=MaterialTheme.shapes.small)
                    FilterChip(!darkAppearance,{platform.changeAppearance(false)},label={Text("浅色")},shape=MaterialTheme.shapes.small)
                }
                Text("随手记快捷键",style=MaterialTheme.typography.titleMedium)
                OutlinedTextField(value,{value=it},label={Text("例如 Ctrl+Shift+Space")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Button(onClick={platform.changeShortcut(value)},shape=MaterialTheme.shapes.small) {Text("保存快捷键")}
                Row {Checkbox(tray,{platform.changeTray(it)});Text("关闭主窗口后留在系统托盘")}
                Text("Step 运行或在托盘驻留时才能同步和提醒；完全退出后不会发送提醒。",style=MaterialTheme.typography.bodySmall)
                status?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            }}}
        }
    }
}
private fun selfTest(path:Path)=runBlocking {
    require(!Files.exists(path) || Files.list(path).use { !it.findAny().isPresent }) {"Self test requires a fresh isolated directory"}
    val runtime=DesktopRuntime(path,DesktopConfig())
    try {
        runtime.initialize()
        val taskId=uid()
        check(runtime.edit { s ->
            StateMachine.edit(s,"task",taskId,ContentActions.save(null,"task","Windows smoke fixture","",1,taskId,Instant.now(),nextAction="Write a heading"))
        }.isSuccess)
        val task=runtime.engine.state.value.items.getValue("task:$taskId")
        check(runtime.edit {NextStepActions.complete(it,taskId,uid(),SmallStepBaseline.from(task),Instant.now())}.isSuccess)
        check(!runtime.engine.state.value.items.getValue("task:$taskId").completed())
        val surface=org.jetbrains.skia.Surface.makeRasterN32Premul(32,32)
        surface.canvas.clear(0xff1c5e49.toInt());surface.close()
        Files.writeString(path.resolve("smoke-result.json"),"""{"version":"0.1.0","os":"${System.getProperty("os.name")}","localSave":true,"stepCompletion":true,"skiaLoaded":true}""")
    } finally {runtime.close()}
}
