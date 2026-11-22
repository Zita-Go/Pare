package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pare.app.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.WeakHashMap

/** Metadata only: no task titles, raw AI content, applications or browsing history. */
private class HintSession(runtime:DesktopRuntime) {
    private val store=DesktopHintStore(runtime.home)
    private val lock=Mutex()
    val memory=MutableStateFlow<DesktopHintMemory?>(null)
    val failure=MutableStateFlow<String?>(null)
    suspend fun load()=withContext(Dispatchers.IO) {lock.withLock {
        if(memory.value==null)try {memory.value=store.load();failure.value=null} catch(_:Exception) {failure.value="本机轻提示设置读取失败，原文件已保留"}
    }}
    suspend fun update(change:(DesktopHintMemory)->DesktopHintMemory):Result<Unit> = withContext(Dispatchers.IO) {lock.withLock {
        runCatching {val next=change(checkNotNull(memory.value){"轻提示设置尚未准备好"});store.save(next);memory.value=next;failure.value=null}.onFailure {failure.value=it.message?:"轻提示设置未能保存，请重试"}
    }}
}
private val hintSessions=WeakHashMap<DesktopRuntime,HintSession>()
@Composable private fun hintSession(runtime:DesktopRuntime):HintSession {
    val session=remember(runtime) {synchronized(hintSessions) {hintSessions.getOrPut(runtime){HintSession(runtime)}}}
    LaunchedEffect(session) {session.load()}
    return session
}

@Composable internal fun IntelligenceHintStrip(runtime:DesktopRuntime,onOpen:(AiTarget)->Unit) {
    val session=hintSession(runtime)
    val memory by session.memory.collectAsState()
    val state by runtime.engine.state.collectAsState()
    val initialized by runtime.engine.initialized.collectAsState()
    val execution by runtime.execution.state.collectAsState()
    val work=rememberWork(runtime)
    var active by remember {mutableStateOf<DesktopHint?>(null)}
    var now by remember {mutableStateOf(Instant.ofEpochMilli(runtime.now().wallMillis))}
    LaunchedEffect(runtime) {while(true) {delay(60_000);now=Instant.ofEpochMilli(runtime.now().wallMillis)}}
    val planId=ContentActions.planId(state,now)
    LaunchedEffect(state,execution,memory,now,initialized) {
        val saved=memory
        if(!initialized||saved==null||!saved.enabled) {active=null;return@LaunchedEffect}
        val candidates=DesktopHintPolicy.candidates(state,planId,now,saved.dailyCapacityMinutes).filterNot {it.kind=="task"&&execution.runFor(it.subjectId)!=null}
        if(active!=null&&candidates.any {it==active}&&saved.ignored[active!!.key]!=active!!.evidence&&(saved.snoozedUntil[active!!.key]?:0)<=now.toEpochMilli())return@LaunchedEffect
        active=DesktopHintPolicy.select(candidates,saved,now)
        if(active!=null)session.update {DesktopHintPolicy.shown(it,now)}
    }
    val hint=active?:return
    StepCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(hint.title,style=MaterialTheme.typography.titleMedium)
                Text(hint.reason,style=MaterialTheme.typography.bodySmall,color=StepMuted)
            }
            StepTextButton({
                val target=when(hint.kind) {"task"->AiTarget(null,hint.subjectId,planId);"idea"->AiTarget(PlanningAIKind.IDEA,hint.subjectId,planId);else->AiTarget(PlanningAIKind.DAY,null,planId)}
                work.run({active=null;onOpen(target)}) {session.update {DesktopHintPolicy.snooze(it,hint,now.plusSeconds(24*3600))}}
            },enabled=!work.busy){Text("用 AI 理清")}
            StepTextButton({work.run({active=null}) {session.update {DesktopHintPolicy.snooze(it,hint,now.plusSeconds(24*3600))}}},enabled=!work.busy){Text("明天再说")}
            StepTextButton({work.run({active=null}) {session.update {DesktopHintPolicy.ignore(it,hint)}}},enabled=!work.busy){Text("忽略")}
        }
    }
    if(work.failed)WorkFeedback(work)
}

@Composable internal fun IntelligencePreferencesPanel(runtime:DesktopRuntime,work:UiWork) {
    val session=hintSession(runtime)
    val memory by session.memory.collectAsState()
    val failure by session.failure.collectAsState()
    var capacity by remember {mutableStateOf("")}
    var capacityEdited by remember {mutableStateOf(false)}
    LaunchedEffect(memory?.dailyCapacityMinutes) {if(!capacityEdited)capacity=memory?.dailyCapacityMinutes?.toString().orEmpty()}
    SectionLabel("本地轻提示","根据你记录的停滞、回看日期和今天的明确投入，在本机判断；不会自动调用模型。")
    Row(verticalAlignment=Alignment.CenterVertically) {
        Checkbox(memory?.enabled==true,{enabled->work.run {session.update {it.copy(enabled=enabled)}}},enabled=memory!=null&&!work.busy)
        Text("显示有依据的轻提示")
    }
    Text("同一时段最多展示一条，24小时冷却。可暂缓到明天，或忽略本次依据；新的推进或回看日期会重新计算。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
    Field(capacity,{capacity=it;capacityEdited=true},"每日个人投入提醒线 · 分钟，留空不判断过载",enabled=memory!=null&&!work.busy)
    val validCapacity=capacity.isBlank()||(capacity.toIntOrNull()?:0) in 5..1440
    if(!validCapacity)Text("请填写5–1440分钟，或留空关闭过载判断",color=MaterialTheme.colorScheme.error)
    StepOutlinedButton({val amount=capacity.toIntOrNull();work.run({capacityEdited=false;work.message="每日个人投入提醒线已保存"}) {session.update {it.copy(dailyCapacityMinutes=amount)}}},enabled=memory!=null&&!work.busy&&capacityEdited&&validCapacity){Text("保存提醒线")}
    failure?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    StepTextButton({work.run({work.message="轻提示暂缓和忽略记录已重置"}) {session.update {DesktopHintMemory(enabled=it.enabled,dailyCapacityMinutes=it.dailyCapacityMinutes)}}},enabled=memory!=null&&!work.busy){Text("重置轻提示的忽略与暂缓")}
}
