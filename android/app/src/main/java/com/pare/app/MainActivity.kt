package com.pare.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.json.*

class MainActivity : ComponentActivity() {
    private var foreground: (() -> Unit)? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: PareViewModel = viewModel()
            foreground = { model.onForeground() }
            MaterialTheme(colorScheme = lightColorScheme(primary=Color(0xFF766146), secondary=Color(0xFF5A7160), background=Color(0xFFF8F5EE), surface=Color(0xFFFFFCF5))) {
                PareScreen(model)
            }
        }
    }
    override fun onResume() { super.onResume(); foreground?.invoke() }
}

@Composable fun PareScreen(vm: PareViewModel) {
    val state by vm.state.collectAsState()
    val planningNow by vm.planningInstant.collectAsState()
    val status by vm.status.collectAsState()
    val session by vm.session.collectAsState()
    val busy by vm.busy.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf<Entity?>(null) }
    var createKind by remember { mutableStateOf<String?>(null) }
    var account by remember { mutableStateOf(false) }
    var showCompleted by remember { mutableStateOf(false) }
    val plan = vm.todayPlan(state,planningNow)
    val ordered = plan?.content()?.get("ordered_task_ids")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
    val win = plan?.content()?.get("daily_win_task_id")?.jsonPrimitive?.contentOrNull
    val tasks = state.items.values.filter { !it.deleted && it.kind=="task" && it.payload?.get("lifecycle")?.jsonPrimitive?.content != "archived" }
    Scaffold(containerColor=MaterialTheme.colorScheme.background,
        bottomBar = { NavigationBar(containerColor=MaterialTheme.colorScheme.surface) {
            listOf("今天","整理","想法").forEachIndexed { index, title -> NavigationBarItem(selected=tab==index,onClick={tab=index},icon={ Text(listOf("○","▦","✧")[index],fontSize=23.sp) },label={Text(title)}) }
        } },
        floatingActionButton = { ExtendedFloatingActionButton(onClick={ createKind=if(tab==2) "idea" else "inbox" },containerColor=MaterialTheme.colorScheme.primary,contentColor=Color.White) { Text(if(tab==2) "+ 记个想法" else "+ 随手记") } }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(20.dp,12.dp,20.dp,100.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Column { Text("PARE",letterSpacing=4.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary); Text(listOf("给今天留一点余地","把事情慢慢理清","让想法长出来")[tab],fontSize=25.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=10.dp)) }; TextButton(onClick={account=true}) {Text(if(session==null) "账号" else "同步") } } }
            item { Text(status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary) }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if(state.items.values.any { it.conflict!=null }) item { Card { Column(Modifier.padding(16.dp)) { Text("有版本需要你决定",fontWeight=FontWeight.Bold); Text("本机和云端都已保留。打开账号与同步，选择要保留的版本。") } } }
            if(tab==0) {
                item { Text("${vm.planningDate(state,planningNow)} · ${vm.planningZone(state,planningNow)}",color=MaterialTheme.colorScheme.secondary); Spacer(Modifier.height(10.dp)); Text("今日重点",fontWeight=FontWeight.Bold) }
                val selected = ordered.mapNotNull { id -> tasks.find { it.id==id } }.filter { showCompleted || !it.completed() }
                if(selected.isEmpty()) item { EmptyNote("今天还很轻", "到整理中选择一件任务，加入今天。") }
                items(selected,key={it.key}) { item -> EntityCard(item,win==item.id,{editor=item},onComplete={vm.complete(item)},onToday={vm.toggleToday(item)},onWin={vm.setWin(item)}) }
                item { TextButton(onClick={showCompleted=!showCompleted}) {Text(if(showCompleted) "隐藏已完成" else "查看已完成")} }
            } else if(tab==1) {
                item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("待整理",fontWeight=FontWeight.Bold); TextButton(onClick={createKind="task"}) { Text("新建任务") } } }
                val inbox = state.items.values.filter { it.kind=="inbox" && !it.deleted }
                if(inbox.isEmpty()) item { Text("随手记的内容会留在这里。",color=MaterialTheme.colorScheme.secondary) }
                items(inbox,key={it.key}) { item -> Card { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(item.title(),Modifier.fillMaxWidth().clickable { editor=item }); TextButton(onClick={vm.organize(item)}) { Text("整理为任务 →") } } } }
                listOf("重要 · 紧急","重要 · 不紧急","紧急 · 不重要","留待以后").forEachIndexed { q, title ->
                    item { Text(title,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=12.dp)) }
                    items(tasks.filter { it.content()?.get("quadrant")?.jsonPrimitive?.intOrNull==q && !it.completed() },key={it.key}) { item -> EntityCard(item,win==item.id,{editor=item},{vm.complete(item)},{vm.toggleToday(item)},null,ordered.contains(item.id)) }
                }
            } else {
                val ideas = state.items.values.filter { it.kind=="idea" && !it.deleted }
                if(ideas.isEmpty()) item { EmptyNote("从一粒种子开始", "记下还没有答案的问题，慢慢探索。") }
                items(ideas,key={it.key}) { item -> Card(onClick={editor=item}) { Column(Modifier.fillMaxWidth().padding(20.dp)) { Text("✧  ${item.content()?.get("stage")?.jsonPrimitive?.content ?: "seed"}",color=MaterialTheme.colorScheme.secondary); Text(item.title(),fontSize=18.sp,modifier=Modifier.padding(top=8.dp)); item.content()?.get("notes")?.jsonPrimitive?.contentOrNull?.let { Text(it,modifier=Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.secondary) } } } }
            }
        }
    }
    if(createKind!=null || editor!=null) EditDialog(editor,createKind ?: editor!!.kind,onDismiss={createKind=null;editor=null}) { text,note,quadrant -> vm.save(editor,createKind ?: editor!!.kind,text,note,quadrant); createKind=null;editor=null }
    if(account) AccountDialog(vm,state,status,session,busy) { account=false }
}
@Composable private fun EmptyNote(title:String,detail:String) { Column(Modifier.fillMaxWidth().background(Color(0xFFF0EBDD),RoundedCornerShape(20.dp)).padding(24.dp)) { Text(title,fontSize=20.sp); Text(detail,modifier=Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.secondary) } }
@Composable private fun EntityCard(item:Entity,win:Boolean,onEdit:()->Unit,onComplete:()->Unit,onToday:()->Unit,onWin:(()->Unit)?=null,inToday:Boolean=true) { Card { Column(Modifier.fillMaxWidth().padding(14.dp)) { Row { Checkbox(item.completed(),onCheckedChange={onComplete()}); Column(Modifier.weight(1f).clickable(onClick=onEdit).padding(top=10.dp)) { if(win) Text("今天最值得的一件事",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall); Text(item.title(),fontSize=17.sp); item.content()?.get("note")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary) } } }; Row { TextButton(onClick=onToday) {Text(if(inToday) "移出今天" else "加入今天")}; if(onWin!=null) TextButton(onClick=onWin) {Text(if(win) "取消重点" else "设为重点")} } } } }
@Composable private fun EditDialog(entity:Entity?,kind:String,onDismiss:()->Unit,onSave:(String,String,Int)->Unit) {
    var text by remember { mutableStateOf(entity?.title() ?: "") }; var note by remember { mutableStateOf(entity?.content()?.get(if(kind=="idea") "notes" else "note")?.jsonPrimitive?.contentOrNull ?: "") }; var quadrant by remember { mutableIntStateOf(entity?.content()?.get("quadrant")?.jsonPrimitive?.intOrNull ?: 1) }
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text(if(entity==null) "记下来" else "编辑")},
        text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(text,{text=it},label={Text("内容")},minLines=2)
                if(kind!="inbox") OutlinedTextField(note,{note=it},label={Text("补充一点背景")},minLines=2)
                if(kind=="task") Column {
                    listOf("重要 · 紧急","重要 · 不紧急","紧急 · 不重要","留待以后").forEachIndexed { index, name ->
                        Row(Modifier.clickable {quadrant=index}) {
                            RadioButton(quadrant==index,{quadrant=index})
                            Text(name,Modifier.padding(top=12.dp))
                        }
                    }
                }
            }
        },
        confirmButton={TextButton(onClick={onSave(text.trim(),note.trim(),quadrant)},enabled=text.isNotBlank()){Text("保存")}},
        dismissButton={TextButton(onClick=onDismiss){Text("取消")}}
    )
}
@Composable private fun AccountDialog(vm:PareViewModel,state:SyncState,status:String,session:Session?,busy:Boolean,onDismiss:()->Unit) {
    var email by remember {mutableStateOf("")};var password by remember {mutableStateOf("")};var signout by remember {mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("账号与同步")},text={Column(Modifier.heightIn(max=540.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(status)
        if(session==null) { Text("登录后先拉取预览。确认开启后，任务、想法、今日计划、背景与规划偏好会存到云端；AI 密钥与请求历史留在本机。",style=MaterialTheme.typography.bodySmall); OutlinedTextField(email,{email=it},label={Text("邮箱")},singleLine=true); OutlinedTextField(password,{password=it},label={Text("密码")},visualTransformation=PasswordVisualTransformation(),singleLine=true); Row { TextButton(onClick={vm.authenticate(email.trim(),password,false);password=""},enabled=!busy&&email.isNotBlank()&&password.isNotEmpty()){Text("登录")};TextButton(onClick={vm.authenticate(email.trim(),password,true);password=""},enabled=!busy&&email.isNotBlank()&&password.isNotEmpty()){Text("注册")} } }
        else { Text(session.email); Text("本机待上传 ${state.items.values.count { it.dirty }} 项 · 冲突 ${state.items.values.count { it.conflict!=null }} 项",style=MaterialTheme.typography.bodySmall); if(!state.enabled) Button(onClick={vm.enableSync()},enabled=!busy) {Text("确认合并并开启同步")}; Row { TextButton(onClick={vm.sync(true)},enabled=!busy){Text("立即同步")}; TextButton(onClick={vm.pauseSync()},enabled=!busy){Text("暂停")}; TextButton(onClick={signout=true},enabled=!busy){Text("退出")} } }
        val conflict = state.items.values.firstOrNull {it.conflict!=null && it.conflictChoice==null}
        if(conflict!=null) { HorizontalDivider(); Text("版本冲突",fontWeight=FontWeight.Bold); Text("本机：${conflict.title()}");Text("云端：${conflict.conflict!!.payload?.get("content")?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: "已删除 / 计划"}");Row {TextButton(onClick={vm.resolve(conflict.key,true)}){Text("保留本机")};TextButton(onClick={vm.resolve(conflict.key,false)}){Text("采用云端")}} }
    }},confirmButton={TextButton(onClick=onDismiss){Text("关闭")}})
    if(signout) AlertDialog(onDismissRequest={signout=false},title={Text("退出账号？")},text={Text("未上传变更会保留在本机，并继续绑定当前账号。重新登录同一账号可继续同步。")},confirmButton={TextButton(onClick={vm.signOut();signout=false}){Text("退出并保留本机")}},dismissButton={TextButton(onClick={signout=false}){Text("取消")}})
}
