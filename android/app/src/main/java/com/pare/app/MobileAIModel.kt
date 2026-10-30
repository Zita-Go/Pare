package com.pare.app

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.*

class MobileAITaskDraft {
    var options by mutableStateOf(MobileAIOptions())
    var input by mutableStateOf<MobileAIRequest?>(null)
    var proposal by mutableStateOf<MobileAIProposal?>(null)
    var undo by mutableStateOf<MobileAIUndo?>(null)
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")
}

/** Activity-scoped: failed writes and rotation retain the reviewed input and proposal. */
class MobileAIModel internal constructor(app:Application,private val vault:MobileAIStore,private val client:MobileAIClient):AndroidViewModel(app) {
    constructor(app:Application):this(app,MobileAIVault(app),MobileAIClient())
    private val drafts=mutableMapOf<String,MobileAITaskDraft>()
    private var saved=MobileAIPrivateData()
    var endpoint by mutableStateOf("")
    var modelName by mutableStateOf("")
    var apiKey by mutableStateOf("")
    var history by mutableStateOf<List<MobileAITrace>>(emptyList());private set
    var ready by mutableStateOf(false);private set
    var saving by mutableStateOf(false);private set
    var settingsStatus by mutableStateOf("");private set
    init {
        viewModelScope.launch {
            try {
                saved=withContext(Dispatchers.IO) {vault.load()}
                endpoint=saved.config.endpoint;modelName=saved.config.model;apiKey=saved.apiKey;history=saved.history
                ready=true
            } catch(_:Exception) {settingsStatus="本机 AI 设置无法读取。可清除本机 AI 资料后重新配置。"}
        }
    }
    fun draft(taskId:String)=drafts.getOrPut(taskId) {MobileAITaskDraft()}
    fun setOptions(taskId:String,options:MobileAIOptions) {
        val d=draft(taskId);if(d.busy || d.undo!=null) return
        d.options=options;d.input=null;d.proposal=null;d.status=""
    }
    fun prepare(state:SyncState,taskId:String) {
        val d=draft(taskId);if(d.busy || d.undo!=null) return
        try {d.input=MobileAIContext.prepare(state,taskId,d.options,history);d.proposal=null;d.status="请查看将发送的内容，再点击生成"}
        catch(e:MobileAIException) {d.status=e.message.orEmpty()}
    }
    /** Explicitly keeps committed task content and relinquishes only the previous round's undo receipt. */
    fun beginNextRound(state:SyncState,taskId:String) {
        val d=draft(taskId);if(d.busy) return
        d.undo=null;d.proposal=null;d.input=null;d.status=""
        prepare(state,taskId)
    }
    fun saveSettings() {
        if(!ready || saving || drafts.values.any {it.busy}) return
        val config=try {MobileAIConfig(endpoint,modelName).validated()} catch(e:MobileAIException) {settingsStatus=e.message.orEmpty();return}
        if(apiKey.isBlank() || apiKey.length>4096 || apiKey.any {it.isISOControl()}) {settingsStatus="请填写有效的 API 密钥";return}
        val next=MobileAIPrivateData(config,apiKey,history);saving=true
        viewModelScope.launch {
            try {withContext(Dispatchers.IO) {vault.save(next)};saved=next;settingsStatus="已加密保存在这台手机"}
            catch(_:Exception) {settingsStatus="本机 AI 设置保存失败，输入已保留"}
            finally {saving=false}
        }
    }
    fun generate(state:SyncState,taskId:String) {
        val d=draft(taskId);val input=d.input ?: return
        if(!ready || saving || drafts.values.any {it.busy}) return
        try {
            MobileAIContext.requireFresh(state,input)
            if(saved.apiKey.isBlank() || saved.config!=MobileAIConfig(endpoint,modelName).validated() || saved.apiKey!=apiKey)
                throw MobileAIException("请先保存本机服务设置，再生成建议")
        } catch(e:MobileAIException) {d.status=e.message.orEmpty();return}
        d.busy=true;d.status="正在请求建议…";val settings=saved
        viewModelScope.launch {
            var trace:MobileAITrace?=null
            try {d.proposal=withContext(Dispatchers.IO) {client.generate(settings.config,settings.apiKey,input) {trace=it}};d.status="建议已生成，确认后才更新下一步"}
            catch(e:MobileAIException) {d.status=e.message.orEmpty()}
            catch(e:CancellationException) {throw e}
            catch(_:Exception) {d.status="AI 请求未完成，预览与草稿已保留"}
            finally {
                trace?.let {record ->
                    val updated=MobileAIHistory.append(history,record)
                    val next=MobileAIPrivateData(saved.config,saved.apiKey,updated)
                    try {withContext(NonCancellable+Dispatchers.IO) {vault.save(next)};saved=next;history=updated}
                    catch(_:Exception) {d.status+="；本机请求记录保存失败"}
                }
                d.busy=false
            }
        }
    }
    fun adopt(taskId:String,onEdit:((SyncState)->SyncState,(Result<Unit>)->Unit)->Unit) {
        val d=draft(taskId);val input=d.input ?: return;val proposal=d.proposal ?: return
        if(d.busy || d.undo!=null) return
        val at=Instant.now();val receipt=MobileAIUndo.from(input,proposal,at);d.busy=true
        onEdit({MobileAIActions.adopt(it,input,proposal,at)}) {result ->
            d.busy=false
            if(result.isSuccess) {d.undo=receipt;d.status="已设为最小下一步，可撤销本次采纳"}
            else d.status=if(result.exceptionOrNull() is MobileAIException) result.exceptionOrNull()?.message.orEmpty() else "保存失败，建议草稿已保留"
        }
    }
    fun undo(taskId:String,onEdit:((SyncState)->SyncState,(Result<Unit>)->Unit)->Unit) {
        val d=draft(taskId);val receipt=d.undo ?: return;if(d.busy) return;d.busy=true
        onEdit({MobileAIActions.undo(it,receipt)}) {result ->
            d.busy=false
            if(result.isSuccess) {d.undo=null;d.status="已撤销本次采纳"}
            else d.status=if(result.exceptionOrNull() is MobileAIException) result.exceptionOrNull()?.message.orEmpty() else "撤销未保存，请稍后重试"
        }
    }
    fun deleteTrace(id:String) = updateHistory(MobileAIHistory.remove(history,id))
    fun clearHistory() = updateHistory(emptyList())
    private fun updateHistory(nextHistory:List<MobileAITrace>) {
        if(!ready || saving || drafts.values.any {it.busy}) return
        saving=true;val next=MobileAIPrivateData(saved.config,saved.apiKey,nextHistory)
        viewModelScope.launch {
            try {withContext(Dispatchers.IO) {vault.save(next)};saved=next;history=nextHistory;settingsStatus="本机记录已删除"}
            catch(_:Exception) {settingsStatus="记录删除失败，请重试"}
            finally {saving=false}
        }
    }
    fun clearPrivateData() {
        if(saving || drafts.values.any {it.busy}) return;saving=true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {vault.clear()};saved=MobileAIPrivateData();history=emptyList()
                endpoint="";modelName="";apiKey="";drafts.clear();ready=true;settingsStatus="本机 AI 设置与记录已清除"
            } catch(_:Exception) {settingsStatus="本机 AI 资料清除失败，请重试"}
            finally {saving=false}
        }
    }
}
