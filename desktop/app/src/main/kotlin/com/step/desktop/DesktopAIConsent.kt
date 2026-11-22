package com.step.desktop

import com.pare.app.*
import kotlinx.serialization.json.*

internal data class DesktopAIConsentSnapshot(val preview:String,val subjectId:String,val background:String?,val feedback:List<DesktopAIFeedback>?)

/** The exact preview is transmitted; supplemental evidence has its own freshness guard in the AI mutex. */
internal object DesktopAIConsent {
    fun prepare(preview:String,state:SyncState,context:DesktopAIContext,subjectId:String,background:Boolean,feedback:Boolean):DesktopAIConsentSnapshot {
        val approvedBackground=if(background)this.background(state)else null
        val approvedFeedback=if(feedback)this.feedback(context,subjectId)else null
        val result=if(!background&&!feedback)preview else {
            val root=wire.parseToJsonElement(preview).jsonObject
            val evidence=root.getValue("evidence").jsonObject
            require("desktopAuthorizedContext" !in evidence){"请重新准备未经扩充的请求预览"}
            val extra=buildJsonObject {
                approvedBackground?.let {put("userProvidedBackground",it)}
                approvedFeedback?.let {entries->put("userFeedback",JsonArray(entries.map {entry->buildJsonObject {
                    put("kind",entry.kind);put("meaning",feedbackLabel(entry.kind));put("comment",entry.comment);put("at",entry.at)
                }}))}
            }
            JsonObject(root+("evidence" to JsonObject(evidence+("desktopAuthorizedContext" to extra)))).toString()
        }
        require(result.toByteArray(Charsets.UTF_8).size<=32_000){"本次参考内容超过32 KB，请减少背景、候选任务或参考范围"}
        return DesktopAIConsentSnapshot(result,subjectId,approvedBackground,approvedFeedback)
    }
    fun requireFresh(snapshot:DesktopAIConsentSnapshot,state:SyncState,context:DesktopAIContext) {
        require(snapshot.background==null||snapshot.background==background(state)){"个人背景已更新，请重新预览后再发送"}
        require(snapshot.feedback==null||snapshot.feedback==feedback(context,snapshot.subjectId)){"建议反馈已更新或删除，请重新预览后再发送"}
    }
    private fun background(state:SyncState):String {
        val profile=state.items["profile:profile"]?.takeUnless {it.deleted}
        require(profile==null||(profile.conflict==null&&profile.conflictGroup.isEmpty())){"背景资料存在同步冲突，请先处理后再授权参考"}
        return ProfileDraft.from(profile).background
    }
    private fun feedback(context:DesktopAIContext,subjectId:String)=context.feedback.filter {it.subjectId==subjectId}.takeLast(3)
}

internal fun feedbackLabel(kind:String)=when(kind) {"too_hard"->"太难";"unhelpful"->"没帮助";"helpful"->"有帮助";else->kind}
