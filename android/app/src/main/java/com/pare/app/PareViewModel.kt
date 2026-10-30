package com.pare.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant

@Serializable data class Session(val owner:String,val email:String,val accessToken:String,val refreshToken:String,val expiresAt:Long)

class PareViewModel(app:Application):AndroidViewModel(app) {
    private val repo=PareRepository(app)
    private val vault=SessionVault(app)
    private val api=SupabaseApi()
    private val network=Mutex()
    @Volatile private var previewReady=false
    @Volatile private var loaded=false
    @Volatile private var syncAgain=false
    val state=MutableStateFlow(SyncState())
    val planningInstant=MutableStateFlow(Instant.now())
    val status=MutableStateFlow("正在读取本机内容")
    val session=MutableStateFlow<Session?>(null)
    val busy=MutableStateFlow(false)
    init {
        viewModelScope.launch {while(isActive) {delay(60_000);planningInstant.value=Instant.now()}}
        viewModelScope.launch(Dispatchers.IO) {
            try {
                state.value=repo.read()
                session.value=vault.load()
                loaded=true
                status.value=if(session.value==null) "本机保存 · 离线可用" else "等待同步"
                sync()
            } catch(e:Exception) {status.value="本机数据读取失败，原数据已保留；请勿清除应用数据"}
        }
    }
    private fun update(block:(SyncState)->SyncState):SyncState = repo.update(block).also {state.value=it}
    private fun edit(block:(SyncState)->SyncState) {
        viewModelScope.launch(Dispatchers.IO) {
            try {check(loaded);update(block);status.value=if(state.value.enabled) "已保存 · 等待同步" else "已保存到本机";sync()}
            catch(e:Exception) {status.value=e.message ?: "保存失败，尚未确认保存"}
        }
    }
    fun save(entity:Entity?,kind:String,text:String,note:String,q:Int) {
        if(text.isBlank()) return
        val id=entity?.id ?: uid()
        edit {s -> StateMachine.edit(s,kind,id,ContentActions.save(s.items["$kind:$id"],kind,text,note,q,id,Instant.now()))}
    }
    fun complete(entity:Entity) = edit {s ->
        val current=s.items.getValue(entity.key)
        val content=current.content()!!.toMutableMap()
        val completed=!current.completed()
        content["completed"]=JsonPrimitive(completed)
        content["lastProgressAt"]=JsonPrimitive(Instant.now().toString())
        if(completed) content["completedAt"]=JsonPrimitive(Instant.now().toString()) else content.remove("completedAt")
        StateMachine.edit(s,current.kind,current.id,JsonObject(current.payload!!.toMutableMap().apply {put("content",JsonObject(content))}))
    }
    fun organize(entity:Entity) {
        val taskID=uid()
        edit {s ->
            val current=s.items.getValue(entity.key)
            require(!current.deleted)
            val payload=ContentActions.save(null,"task",current.title(),"",1,taskID,Instant.now()).toMutableMap()
            val c=payload.getValue("content").jsonObject.toMutableMap()
            current.content()?.get("suggestedRemindAt")?.let {c["remindAt"]=it}
            payload["content"]=JsonObject(c)
            var next=StateMachine.edit(s,"task",taskID,JsonObject(payload))
            next=StateMachine.edit(next,"inbox",current.id,null,true)
            StateMachine.group(next,setOf("task:$taskID",current.key))
        }
    }
    fun planningZone(s:SyncState,now:Instant=planningInstant.value)=ContentActions.planId(s,now).substringAfter('@')
    fun planningDate(s:SyncState,now:Instant=planningInstant.value)=ContentActions.planId(s,now).substringBefore('@')
    fun todayPlan(s:SyncState,now:Instant=planningInstant.value)=s.items["day_plan:${ContentActions.planId(s,now)}"]?.takeUnless {it.deleted}
    private fun editPlan(s:SyncState,change:(MutableMap<String,JsonElement>)->Unit):SyncState {
        val id=ContentActions.planId(s,Instant.now())
        val old=s.items["day_plan:$id"]
        val content=old?.content()?.toMutableMap() ?: mutableMapOf("day_key" to JsonPrimitive(id.substringBefore('@')),"time_zone" to JsonPrimitive(id.substringAfter('@')),"ordered_task_ids" to JsonArray(emptyList()),"task_plans" to JsonArray(emptyList()))
        change(content)
        return StateMachine.edit(s,"day_plan",id,JsonObject((old?.payload ?: JsonObject(emptyMap())).toMutableMap().apply {put("content",JsonObject(content))}))
    }
    fun toggleToday(e:Entity) = edit {s -> editPlan(s) {content ->
        val ids=content.getValue("ordered_task_ids").jsonArray.map {it.jsonPrimitive.content}.toMutableList()
        if(e.id in ids) {ids.remove(e.id);if(content["daily_win_task_id"]?.jsonPrimitive?.contentOrNull==e.id)content.remove("daily_win_task_id");content["task_plans"]?.jsonArray?.let {plans -> content["task_plans"]=JsonArray(plans.filter {it.jsonObject["task_id"]?.jsonPrimitive?.content!=e.id})}}
        else ids.add(e.id)
        content["ordered_task_ids"]=JsonArray(ids.map {JsonPrimitive(it)})
    }}
    fun setWin(e:Entity) = edit {s -> editPlan(s) {c ->
        val ids=c.getValue("ordered_task_ids").jsonArray.map {it.jsonPrimitive.content}.toMutableList()
        if(e.id !in ids) ids.add(e.id)
        c["ordered_task_ids"]=JsonArray(ids.map {JsonPrimitive(it)})
        if(c["daily_win_task_id"]?.jsonPrimitive?.contentOrNull==e.id)c.remove("daily_win_task_id") else c["daily_win_task_id"]=JsonPrimitive(e.id)
    }}
    private fun launchNetwork(action:suspend ()->Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if(!network.tryLock()) return@launch
            busy.value=true
            try {action()}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {status.value=if(e is java.io.IOException) "等待联网 · 变更已保存在本机" else e.message ?: "同步失败，变更已保留"}
            finally {
                busy.value=false;network.unlock()
                if(syncAgain) {syncAgain=false;sync()}
            }
        }
    }
    private fun activeSession():Session {
        var current=requireNotNull(session.value) {"请先登录"}
        require(state.value.owner==current.owner)
        if(current.expiresAt<=System.currentTimeMillis()/1000+60) {
            val refreshed=api.refresh(current)
            require(refreshed.owner==current.owner) {"登录账号发生变化"}
            vault.save(refreshed);session.value=refreshed;current=refreshed
        }
        return current
    }
    private fun pullAll() {
        do {
            val page=api.pull(activeSession(),state.value.cursor)
            update {StateMachine.pull(it,page)}
        } while(page.more)
        previewReady=true
    }
    fun authenticate(email:String,password:String,signup:Boolean) = launchNetwork {
        check(loaded)
        status.value=if(signup) "正在注册" else "正在登录"
        val next=api.login(email,password,signup)
        if(next==null) {status.value="请到邮箱完成验证，再回到这里登录";return@launchNetwork}
        update {StateMachine.bind(it,next.owner).copy(enabled=false)}
        vault.save(next);session.value=next
        status.value="正在拉取云端，尚未上传"
        pullAll()
        status.value="已拉取预览 · 请确认合并并开启同步"
    }
    fun enableSync() {
        if(!previewReady) {status.value="请先点立即同步，完成云端拉取预览";return}
        edit {s ->require(session.value?.owner==s.owner);s.copy(enabled=true)}
    }
    fun pauseSync() {edit {it.copy(enabled=false)};previewReady=false}
    fun onForeground() {planningInstant.value=Instant.now();sync()}
    fun sync(manual:Boolean=false) {
        if(!loaded || session.value==null || (!state.value.enabled && !manual)) return
        if(busy.value) {syncAgain=true;return}
        launchNetwork {
            status.value="正在同步"
            // Replay an immutable lost-response operation before observing newer revisions.
            if(state.value.enabled) state.value.pending?.let {pending ->
                val reply=api.push(activeSession(),pending)
                update {StateMachine.accept(it,reply)}
            }
            pullAll()
            if(state.value.enabled) {
                var batches=0
                while(state.value.enabled && batches++<100) {
                    val pending=update {StateMachine.prepare(it)}.pending ?: break
                    val reply=api.push(activeSession(),pending)
                    update {StateMachine.accept(it,reply)}
                    if(reply.status=="conflict") break
                }
                pullAll()
            }
            val snapshot=state.value
            status.value=when {
                snapshot.items.values.any {it.conflict!=null} -> "需要处理冲突 · 两个版本都已保留"
                !snapshot.enabled -> "已拉取预览 · 同步未开启"
                snapshot.pending!=null || snapshot.items.values.any {it.dirty} -> "等待同步 · 仍有本机变更"
                else -> "已同步"
            }
        }
    }
    fun resolve(key:String,local:Boolean)=edit {StateMachine.resolve(it,key,local)}
    fun signOut() = launchNetwork {
        val old=session.value
        update {it.copy(enabled=false)}
        vault.clear();session.value=null;previewReady=false
        status.value="已退出 · 未上传变更保留并绑定原账号"
        if(old!=null) runCatching {api.logout(old)}
    }
    override fun onCleared() {super.onCleared();repo.close()}
}
