package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import androidx.compose.ui.res.painterResource
import com.pare.app.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
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
    if(args.any {it in setOf("--self-test","--system-self-test","--upgrade-self-test")}) {
        // jpackage's GUI launcher shows a modal error when main throws. Diagnostic commands
        // must instead finish with a machine-readable exit status, including argument failures.
        val result=try {
            val diagnosticPath=home(args)
            when {
                "--self-test" in args->selfTest(diagnosticPath)
                "--system-self-test" in args->systemSelfTest(diagnosticPath)
                else->upgradeSelfTest(diagnosticPath)
            }
            0
        } catch(failure:Throwable) {failure.printStackTrace(System.err);1}
        kotlin.system.exitProcess(result)
    }
    val path=home(args)
    val delivery=args.indexOf("--deliver-reminder")
    val activation=args.indexOf("--notification-activation")
    if(delivery>=0 || activation>=0) {
        val signal=runCatching {
            if(delivery>=0)DesktopSignal(args[delivery+1],"deliver").also(DesktopSignals::validate)
            else DesktopSignals.activation(args[activation+1],DesktopWindows(path).scheme)
        }.getOrElse {return}
        DesktopSignals.enqueue(path,signal)
        // Another instance drains the private inbox; never contend for its data or open a second UI.
        if(runCatching {DesktopStore(path).close()}.isFailure)return
        if(signal.action!="open") {
            if(signal.action=="deliver" && runCatching {
                wire.decodeFromString<DesktopSystemSettings>(Files.readString(path.resolve("system-settings.json"))).afterExitReminders
            }.getOrDefault(false)==false)return
            runBlocking {
                val runtime=runCatching {DesktopRuntime(path,configuration())}.getOrNull() ?: return@runBlocking
                try {runtime.initialize();runtime.finishSystemDelivery()}finally {runtime.close()}
            }
            return
        }
    }
    application {
        var visible by remember {mutableStateOf("--start-hidden" !in args || !SystemTray.isSupported())}
        var capture by remember {mutableStateOf(false)}
        var captureActivation by remember {mutableIntStateOf(0)}
        var mainActivation by remember {mutableIntStateOf(0)}
        fun showCapture() {capture=true;captureActivation++}
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
        val platform=remember {runtime?.let {DesktopPlatform(path) {if(capture)capture=false else showCapture()}}}
        val optionsContent: (@Composable (DesktopOptionsDraft)->Unit)?=if(platform==null)null else {draft->DesktopOptionsContent(platform,draft)}
        val taskRequest by (runtime?.requestedTask ?: MutableStateFlow<String?>(null)).collectAsState()
        val installerRequest by (runtime?.requestedInstaller ?: MutableStateFlow<Path?>(null)).collectAsState()
        val widgetVisible by (platform?.widgetVisible ?: MutableStateFlow(false)).collectAsState()
        val widgetPinned by (platform?.widgetPinned ?: MutableStateFlow(true)).collectAsState()
        fun finishQuit() {
            if(quitting)return
            quitting=true
            uiScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val installer=if(runtime?.requestedInstaller?.value!=null)runtime.verifiedInstaller() else null
                        platform?.close();runtime?.close()
                        if(installer!=null)ProcessBuilder(installer.toString()).start()
                    }
                    exitApplication()
                } catch(_:Exception) {quitting=false;initializationError="退出或安装未能完成。资料已保留，请重新打开 Step 后重试。"}
            }
        }
        fun quit() {
            if(DesktopDraftStatus.hasUnsavedEdits.value)confirmExit=true else finishQuit()
        }
        val requestClose:()->Unit={
            if(platform?.keepInTray?.value==true && trayIcon.value!=null)visible=false else quit()
        }
        LaunchedEffect(runtime) {runtime?.let {try {it.initialize();if(!it.engine.initialized.value)initializationError=it.error.value}catch(e:Exception){initializationError=e.message ?: "资料读取失败，原文件已保留"}}}
        LaunchedEffect(taskRequest) {if(taskRequest!=null)visible=true}
        LaunchedEffect(installerRequest) {if(installerRequest!=null)quit()}
        DisposableEffect(runtime) {
            var icon:TrayIcon?=null
            if(SystemTray.isSupported()) {
                val image=ImageIO.read(object {}.javaClass.getResource("/step.png"))
                val menu=PopupMenu()
                fun entry(title:String,action:()->Unit) {menu.add(MenuItem(title).apply {addActionListener(ActionListener {action()})})}
                entry("打开 Step") {visible=true;mainActivation++};entry("随手记") {showCapture()};entry("桌面选项") {visible=true;desktopOptions=true}
                entry("桌面小窗") {platform?.changeWidget(true)}
                menu.addSeparator();entry("退出 Step") {quit()}
                icon=TrayIcon(image,"Step",menu).apply {isImageAutoSize=true;addActionListener {visible=true}}
                runCatching {SystemTray.getSystemTray().add(icon);trayIcon.value=icon}.onFailure {visible=true;platform?.status?.value="系统托盘不可用，请保持窗口以接收提醒"}
            }
            onDispose {icon?.let {runCatching {SystemTray.getSystemTray().remove(it)}}}
        }
        Window(visible=visible,onCloseRequest=requestClose,title="Step",icon=painterResource("step.png"),state=rememberFittedWindowState(1160,780)) {
            KeepWindowInWorkArea(window,960,680,visible)
            LaunchedEffect(visible,mainActivation) {if(visible)SwingUtilities.invokeLater {
                if(window.isVisible){window.toFront();window.requestFocus()}
            }}
            CompositionLocalProvider(LocalDesktopWindow provides window) {
            LaunchedEffect(window,darkAppearance,visible) {applyWindowsFrameAppearance(window,darkAppearance)}
            if(runtime!=null && initializationError==null)Box {
                CompositionLocalProvider(LocalDesktopOptionsContent provides optionsContent) {
                    DesktopUi(runtime,{showCapture()},requestClose,{desktopOptions=true},taskRequest,{runtime.requestedTask.value=null})
                }
                notices.firstOrNull()?.let {notice -> DesktopTheme {Box(Modifier.fillMaxSize()) {
                    Surface(modifier=Modifier.align(Alignment.BottomEnd).padding(20.dp).widthIn(max=460.dp),shadowElevation=8.dp,shape=MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(notice.first,style=MaterialTheme.typography.titleMedium);Text(notice.second)
                            TextButton(onClick={notices.removeAt(0)}) {Text(if(notices.size>1) "下一条（${notices.size-1}）" else "知道了")}
                        }
                    }
                }}}
            }
            else DesktopTheme {Column(Modifier.fillMaxSize().padding(40.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("Step 暂时无法打开资料",style=MaterialTheme.typography.headlineMedium)
                Text(initializationError ?: "正在准备")
                Text("原有文件不会被清空。若另一个 Step 正在运行，请使用已有窗口。")
                if(runtime!=null && !runtime.engine.initialized.value)Button(onClick={uiScope.launch {
                    val recovered=runtime.recoverAutomaticBackup()
                    initializationError=if(recovered.isSuccess)null else recovered.exceptionOrNull()?.message ?: "恢复失败，原文件仍保留"
                }},shape=MaterialTheme.shapes.small) {Text("保留坏文件并恢复上一份自动备份")}
                Button(onClick={quit()},shape=MaterialTheme.shapes.small) {Text("关闭")}
            }}
            }
        }
        if(runtime!=null) Window(visible=capture,onCloseRequest={capture=false},title="Step · 随手记",icon=painterResource("step.png"),alwaysOnTop=true,resizable=false,state=rememberFittedWindowState(460,520)) {
            KeepWindowInWorkArea(window,340,260,capture)
            LaunchedEffect(capture,captureActivation) {if(capture)SwingUtilities.invokeLater {
                if(window.isVisible){window.toFront();window.requestFocus()}
            }}
            CompositionLocalProvider(LocalDesktopWindow provides window) {
            LaunchedEffect(window,darkAppearance,capture) {applyWindowsFrameAppearance(window,darkAppearance)}
            StepCaptureUi(runtime,onClose={capture=false},visible=capture,onOrganize={visible=true;mainActivation++})
            }
        }
        if(runtime!=null && platform!=null)Window(visible=widgetVisible,onCloseRequest={platform.changeWidget(false)},title="Step · 桌面小窗",alwaysOnTop=widgetPinned,state=rememberFittedWindowState(390,560),icon=painterResource("step.png")) {
            KeepWindowInWorkArea(window,340,420,widgetVisible)
            CompositionLocalProvider(LocalDesktopWindow provides window) {
            LaunchedEffect(window,darkAppearance) {applyWindowsFrameAppearance(window,darkAppearance)}
            DesktopWidgetUi(runtime,{id->runtime.requestedTask.value=id;visible=true},{showCapture()})
            }
        }
        if(confirmExit) DialogWindow(onCloseRequest={confirmExit=false;runtime?.requestedInstaller?.value=null},title=if(installerRequest!=null)"安装更新前保存草稿"else"退出 Step？",icon=painterResource("step.png"),resizable=false,state=rememberFittedDialogState(420,220)) {
            KeepWindowInWorkArea(window,420,220,true)
            DesktopTheme {Surface {Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Text("还有未保存的编辑",style=MaterialTheme.typography.titleLarge)
                Text("退出会丢弃这些草稿。已保存的任务会保留，正在进行的专注会暂停。")
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick={confirmExit=false;runtime?.requestedInstaller?.value=null;visible=true},shape=MaterialTheme.shapes.small) {Text("返回保存")}
                    Button(onClick={confirmExit=false;finishQuit()},shape=MaterialTheme.shapes.small) {Text("放弃草稿并退出")}
                }
            }}}
        }
        if(desktopOptions && platform!=null) DialogWindow(onCloseRequest={desktopOptions=false},title="Step · 桌面选项",icon=painterResource("step.png"),resizable=false,state=rememberFittedDialogState(470,570)) {
            KeepWindowInWorkArea(window,340,260,true)
            LaunchedEffect(window,darkAppearance) {applyWindowsFrameAppearance(window,darkAppearance)}
            DesktopTheme {Surface {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                DesktopOptionsContent(platform)
            }}}
        }
    }
}
private fun selfTest(path:Path)=runBlocking {
    require(!Files.exists(path) || Files.list(path).use { !it.findAny().isPresent }) {"Self test requires a fresh isolated directory"}
    val chineseDate=java.time.LocalDate.of(2026,11,22).format(
        java.time.format.DateTimeFormatter.ofPattern("M月d日 · EEEE",java.util.Locale.CHINESE))
    check(chineseDate=="11月22日 · 星期日") {"Packaged runtime must include Chinese weekday data: $chineseDate"}
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
        Files.writeString(path.resolve("smoke-result.json"),"""{"version":"${DesktopUpdates.CURRENT_VERSION}","os":"${System.getProperty("os.name")}","localSave":true,"stepCompletion":true,"skiaLoaded":true,"chineseWeekday":true,"chineseDate":"$chineseDate"}""")
    } finally {runtime.close()}
}
private fun upgradeSelfTest(path:Path)=runBlocking {
    // This verifier never creates fixtures. It only accepts the isolated old-version smoke data.
    require(Files.isRegularFile(path.resolve("smoke-result.json")) && Files.isRegularFile(path.resolve("sync-state.json"))) {"Upgrade verification requires the existing old-version self-test directory"}
    require(!Files.exists(path.resolve("session.dpapi")) && !Files.exists(path.resolve("ai.dpapi"))) {"Upgrade verification refuses credential-bearing directories"}
    val previousSmoke=wire.parseToJsonElement(Files.readString(path.resolve("smoke-result.json"))).jsonObject
    check(previousSmoke["localSave"]?.jsonPrimitive?.booleanOrNull==true && previousSmoke["stepCompletion"]?.jsonPrimitive?.booleanOrNull==true)
    val beforeBytes=Files.readAllBytes(path.resolve("sync-state.json"))
    val before=DesktopStore(path).use {it.read()}
    require(before.owner==null && before.items.size==1)
    val task=before.items.values.single()
    require(task.kind=="task" && task.title()=="Windows smoke fixture" && !task.completed())
    val steps=task.content()?.get("completedSteps")?.jsonArray ?: error("Old-version step history is missing")
    require(steps.size==1 && steps.single().jsonObject["text"]?.jsonPrimitive?.content=="Write a heading")
    val oldExecution=DesktopExecutionPersistence(path).load()
    require(oldExecution?.focus==null && oldExecution?.external.orEmpty().isEmpty())
    val runtime=DesktopRuntime(path,DesktopConfig())
    try {
        runtime.initialize()
        check(runtime.engine.initialized.value && runtime.executionReady.value)
        check(runtime.engine.state.value==before)
        val retained=runtime.engine.state.value.items.getValue(task.key)
        check(retained.id==task.id && retained.content()?.get("completedSteps")==JsonArray(steps))
        check(runtime.execution.state.value.focus==null && runtime.execution.state.value.external.isEmpty())
    } finally {runtime.close()}
    check(Files.readAllBytes(path.resolve("sync-state.json")).contentEquals(beforeBytes))
    Files.writeString(path.resolve("upgrade-self-test.json"),"""{"version":"${DesktopUpdates.CURRENT_VERSION}","sameTask":true,"sameStepHistory":true,"executionEmpty":true,"storeUnchanged":true}""")
}
private fun systemSelfTest(path:Path) {
    require(!Files.exists(path) || Files.list(path).use {!it.findAny().isPresent}) {"System self-test requires a fresh isolated directory"}
    Files.createDirectories(path)
    val native=DesktopWindows(path)
    check(native.supported) {"System self-test requires the packaged Windows executable"}
    try {
        native.configure(DesktopSystemSettings(startAtLogin=true,systemNotifications=true,afterExitReminders=true))
        val events=listOf(DesktopScheduledNotice("fixture:${uid()}",System.currentTimeMillis()+3_600_000))
        native.schedule(events);native.schedule(events) // Re-registering does not duplicate tasks.
        val registered=native.inspect()
        check(registered["startAtLogin"]!!.jsonPrimitive.boolean && registered["shortcut"]!!.jsonPrimitive.boolean && registered["protocol"]!!.jsonPrimitive.boolean)
        check(registered["tasks"]!!.jsonPrimitive.int==1 && registered["interactiveTasksValid"]!!.jsonPrimitive.boolean)
    } finally {native.configure(DesktopSystemSettings())}
    val cleaned=native.inspect()
    check(!cleaned["startAtLogin"]!!.jsonPrimitive.boolean && !cleaned["shortcut"]!!.jsonPrimitive.boolean && !cleaned["protocol"]!!.jsonPrimitive.boolean && !cleaned["taskFolder"]!!.jsonPrimitive.boolean && cleaned["tasks"]!!.jsonPrimitive.int==0)
    Files.writeString(path.resolve("system-self-test.json"),"""{"registered":true,"duplicateSafe":true,"interactiveUser":true,"cleaned":true}""")
}
