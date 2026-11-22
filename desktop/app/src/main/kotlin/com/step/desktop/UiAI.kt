package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pare.app.*
import java.time.Instant

internal data class AiTarget(val kind:PlanningAIKind?,val subjectId:String?,val planId:String)
@Composable internal fun AiUi(runtime:DesktopRuntime,target:AiTarget,onClose:()->Unit) {
    val state by runtime.engine.state.collectAsState();val settings by runtime.aiSettings.collectAsState()
    val work=rememberWork(runtime)
    val localContext by runtime.aiContext.collectAsState()
    var includeBackground by remember{mutableStateOf(false)}
    var includeFeedback by remember{mutableStateOf(false)}
    var consent by remember{mutableStateOf<DesktopAIConsentSnapshot?>(null)}
    var historyOpen by remember{mutableStateOf(false)}
    var candidatesOpen by remember{mutableStateOf(false)}
    var sentPreviewOpen by remember{mutableStateOf(false)}
    val feedbackSubject=target.subjectId?:"day:${target.planId}"
    val initialCapacity=remember {ProfileDraft.from(state.items["profile:profile"]).planningMinutes.toString()}
    val serviceDraft=remember{UiAIServiceDraft(settings.config)}
    var configure by remember{mutableStateOf(false)}
    var extra by remember{mutableStateOf(MobileAIOptions())}
    var selectedTasks by remember{mutableStateOf(if(target.kind!=null)PlanningAIContext.defaults(state,target.kind,target.subjectId,target.planId).taskIds else emptyList())}
    val initialTasks=remember{selectedTasks}
    var capacity by remember{mutableStateOf(initialCapacity)};var energy by remember{mutableStateOf("一般")}
    var nextInput by remember{mutableStateOf<MobileAIRequest?>(null)};var planInput by remember{mutableStateOf<PlanningAIRequest?>(null)}
    var nextProposal by remember{mutableStateOf<MobileAIProposal?>(null)};var planProposal by remember{mutableStateOf<PlanningAIProposal?>(null)}
    var nextUndo by remember{mutableStateOf<MobileAIUndo?>(null)};var planUndo by remember{mutableStateOf<PlanningAIUndo?>(null)}
    var selectedActions by remember{mutableStateOf<Set<String>>(emptySet())}
    fun invalidate(){sentPreviewOpen=false;consent=null;nextInput=null;planInput=null;nextProposal=null;planProposal=null;selectedActions=emptySet()}
    val adopted=nextUndo!=null||planUndo!=null
    UiModal(target.kind?.title?:"找到最小下一步",onClose,work,width=880,dirty=serviceDraft.dirty || (!adopted&&(nextProposal!=null || planProposal!=null || extra!=MobileAIOptions() || selectedTasks!=initialTasks || capacity!=initialCapacity || energy!="一般" || includeBackground || includeFeedback))) {
        Row(verticalAlignment=Alignment.CenterVertically){Text("选择参考 → 预览发送内容 → 审阅建议 → 确认采纳",modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=StepMuted);StepTextButton({configure=!configure},enabled=!work.busy){Text(if(configure)"收起设置"else"AI 服务设置")}}
        StepTextButton({historyOpen=true},enabled=!work.busy){Text("查看本机请求与反馈")}
        if(configure || !settings.hasKey) {AISettingsPanel(runtime,work,serviceDraft);HorizontalDivider()}
        if(!adopted && nextProposal==null && planProposal==null) {
            SectionLabel("本次允许参考的内容","当前对象是必要上下文；以下额外参考默认关闭。")
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("相关想法" to extra.ideas,"长期目标" to extra.goals,"已确认偏好" to extra.preferences,"本机历史" to extra.history).forEachIndexed {index,(label,value)->
                    FilterChip(value,{extra=when(index){0->extra.copy(ideas=!value);1->extra.copy(goals=!value);2->extra.copy(preferences=!value);else->extra.copy(history=!value)};invalidate()},label={Text(label)},enabled=!work.busy)
                }
                FilterChip(includeBackground,{includeBackground=!includeBackground;invalidate()},label={Text("个人背景")},enabled=!work.busy)
                FilterChip(includeFeedback,{includeFeedback=!includeFeedback;invalidate()},label={Text("建议反馈")},enabled=!work.busy)
            }
            if(includeBackground)Text("仅参考在「背景与偏好」中明确填写的个人背景。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
            if(includeFeedback)Text("参考本对象最近3条反馈，不包含其他任务的评价。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
            if(target.kind!=null) {
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){Field(capacity,{capacity=it;invalidate()},"本次可用分钟",Modifier.width(180.dp),enabled=!work.busy);ChoiceRow(listOf("低" to "精力较低","一般" to "精力一般","充足" to "精力充足"),energy,!work.busy){energy=it;invalidate()}}
                val candidates=PlanningAIContext.eligible(state,target.kind,target.subjectId)
                if(candidates.isNotEmpty()) {
                    StepTextButton({candidatesOpen=!candidatesOpen},enabled=!work.busy){Text("候选行动 · 已选 ${selectedTasks.size} / 8 件 · ${if(candidatesOpen)"收起"else"调整"}")}
                    if(candidatesOpen)candidates.forEach {task->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(task.id in selectedTasks,{yes->selectedTasks=if(yes)(selectedTasks+task.id).distinct()else selectedTasks-task.id;invalidate()},enabled=!work.busy&&(task.id in selectedTasks||selectedTasks.size<8));Text(task.title(),modifier=Modifier.weight(1f))}}
                }
            }
            StepButton({
                val background=includeBackground;val feedback=includeFeedback
                if(target.kind==null) {
                    var prepared:MobileAIRequest?=null;var authorized:DesktopAIConsentSnapshot?=null;val options=extra
                    work.run({nextInput=prepared;consent=authorized}){runCatching {
                        val snapshot=runtime.engine.state.value
                        val input=MobileAIContext.prepare(snapshot,requireNotNull(target.subjectId),options,runtime.aiHistory.value)
                        authorized=DesktopAIConsent.prepare(input.preview,snapshot,runtime.aiContext.value,feedbackSubject,background,feedback)
                        prepared=input.copy(preview=authorized!!.preview);Unit
                    }}
                } else {
                    var prepared:PlanningAIRequest?=null;var authorized:DesktopAIConsentSnapshot?=null;val tasks=selectedTasks;val amount=capacity;val e=energy;val options=extra
                    work.run({planInput=prepared;consent=authorized}){runCatching {
                        val snapshot=runtime.engine.state.value
                        val input=PlanningAIContext.prepare(snapshot,target.kind,target.subjectId,target.planId,PlanningAIOptions(tasks,amount.toIntOrNull()?:error("请输入 5–480 分钟"),e,options),runtime.aiHistory.value)
                        authorized=DesktopAIConsent.prepare(input.preview,snapshot,runtime.aiContext.value,feedbackSubject,background,feedback)
                        prepared=input.copy(preview=authorized!!.preview);Unit
                    }}
                }
            },enabled=!work.busy){Text("预览将发送的内容")}
        }
        val preview=nextInput?.preview?:planInput?.preview
        if(preview!=null&&!adopted) {
            HorizontalDivider()
            if(nextProposal==null&&planProposal==null)SectionLabel("发送内容预览","确认后仅发送以下内容到你配置的模型服务。此步骤尚未发送。")
            else StepTextButton({sentPreviewOpen=!sentPreviewOpen}){Text(if(sentPreviewOpen)"收起已发送的上下文"else"查看本次已发送的上下文")}
            if((nextProposal==null&&planProposal==null)||sentPreviewOpen)SelectionContainer {Text(preview,style=MaterialTheme.typography.bodySmall,modifier=Modifier.fillMaxWidth())}
            if(nextProposal==null&&planProposal==null)StepButton({
                val ni=nextInput;val pi=planInput;val authorized=requireNotNull(consent)
                if(ni!=null) {var proposal:MobileAIProposal?=null;work.run({nextProposal=proposal;work.message="建议已生成，尚未修改任务"}){runtime.generateAI(ni){DesktopAIConsent.requireFresh(authorized,runtime.engine.state.value,runtime.aiContext.value)}.map {proposal=it;Unit}}}
                else if(pi!=null) {var proposal:PlanningAIProposal?=null;work.run({planProposal=proposal;selectedActions=proposal?.actions?.map {it.id}?.toSet().orEmpty();work.message="勾选要采纳的建议，确认后才修改资料"}){runtime.generatePlan(pi){DesktopAIConsent.requireFresh(authorized,runtime.engine.state.value,runtime.aiContext.value)}.map {proposal=it;Unit}}}
            },enabled=settings.hasKey&&!work.busy){Text("确认发送并生成建议")}
        }
        nextProposal?.let {p->
            HorizontalDivider();SectionLabel("建议的下一步","${p.minutes} 分钟的起点")
            Text(p.nextAction,style=MaterialTheme.typography.titleLarge);Text("做到这里就停：${p.stopCriterion}");Text("依据：${p.reason}",color=StepMuted)
            if(!adopted)StepButton({val input=nextInput!!;val at=Instant.now();work.edit({nextUndo=MobileAIUndo.from(input,p,at);work.message="已采纳；可以撤销本次建议"}){MobileAIActions.adopt(it,input,p,at)}},enabled=!work.busy){Text("采纳为下一步")}
        }
        planProposal?.let {p->
            HorizontalDivider();SectionLabel("审阅建议",p.summary)
            p.actions.forEach {action->
                Row(verticalAlignment=Alignment.Top) {
                    Checkbox(action.id in selectedActions,{selectedActions=if(it)selectedActions+action.id else selectedActions-action.id},enabled=!work.busy&&!adopted)
                    Column(Modifier.weight(1f).padding(top=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(action.title.ifBlank {action.nextAction.ifBlank {action.text.ifBlank {state.items["task:${action.sourceId}"]?.title()?:"建议行动"}}},style=MaterialTheme.typography.titleMedium)
                        if(action.text.isNotBlank())Text(action.text)
                        if(action.nextAction.isNotBlank())Text("下一步：${action.nextAction}")
                        if(action.stopCriterion.isNotBlank())Text("停止标准：${action.stopCriterion}")
                        if(action.minutes>0)Text("${action.minutes} 分钟 · ${if(action.allocationKind=="check")"检查投入"else"个人投入"}",style=MaterialTheme.typography.bodySmall,color=StepGreen)
                        Text(action.reason,style=MaterialTheme.typography.bodySmall,color=StepMuted)
                    }
                }
            }
            if(!adopted)StepButton({val input=planInput!!;val selected=selectedActions;var receipt:PlanningAIUndo?=null;work.edit({planUndo=receipt;work.message="已采纳所选建议；可以撤销本次采纳"}){before->PlanningReviewActions.adopt(before,input,p,selected,Instant.now()).also {receipt=PlanningAIUndo.capture(before,it)}}},enabled=!work.busy&&selectedActions.isNotEmpty()){Text("采纳选中的 ${selectedActions.size} 项")}
        }
        if(nextProposal!=null||planProposal!=null) {
            val requestId=nextInput?.id?:planInput?.id
            val recorded=localContext.feedback.lastOrNull {it.requestId==requestId}
            HorizontalDivider(color=StepLine)
            Text("这次建议对你有帮助吗？",style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("too_hard","unhelpful","helpful").forEach {kind->
                    FilterChip(recorded?.kind==kind,{
                        val id=requestId?:return@FilterChip
                        val value=DesktopAIFeedback(uid(),id,feedbackSubject,kind,"",Instant.now().toString())
                        work.run({work.message="反馈已保存到本机"}){runtime.recordAIFeedback(value)}
                    },label={Text(feedbackLabel(kind))},enabled=!work.busy)
                }
            }
            Text("反馈只保存在本机；下一轮勾选「建议反馈」并预览后，才会交给模型参考。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
        }
        if(adopted) {
            Text("采纳已保存到本机。之后的编辑会受到保护，撤销不会覆盖后续更改。",color=StepGreen)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                StepOutlinedButton({val n=nextUndo;val p=planUndo;work.edit({nextUndo=null;planUndo=null;invalidate();work.message="已撤销本次采纳"}){if(n!=null)MobileAIActions.undo(it,n)else requireNotNull(p).apply(it)}},enabled=!work.busy){Text("撤销本次采纳")}
                StepTextButton({nextUndo=null;planUndo=null;invalidate()},enabled=!work.busy){Text("开始新一轮")}
            }
        } else if(nextProposal!=null || planProposal!=null)StepTextButton({invalidate()},enabled=!work.busy){Text("放弃建议，重新预览")}
    }
    if(historyOpen)AIHistoryUi(runtime){historyOpen=false}
}
