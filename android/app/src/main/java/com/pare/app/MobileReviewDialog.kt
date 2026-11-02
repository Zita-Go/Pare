package com.pare.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant

class MobileHintPreferences(context:Context) {
    private val prefs=context.getSharedPreferences("pare-local-hints",Context.MODE_PRIVATE)
    fun enabled()=prefs.getBoolean("enabled",true)
    @Synchronized fun setEnabled(value:Boolean) {
        val old=enabled()
        if(!prefs.edit().putBoolean("enabled",value).commit()) {
            prefs.edit().putBoolean("enabled",old).apply()
            error("轻提示设置未保存")
        }
    }
    fun visible(hint:ReviewAttention,now:Instant)=enabled() && now.toEpochMilli()>=prefs.getLong("deferred:${hint.key}",0)
    @Synchronized fun defer(hint:ReviewAttention,now:Instant) {
        val key="deferred:${hint.key}";val old=prefs.getLong(key,0)
        if(!prefs.edit().putLong(key,now.plusSeconds(7*86400).toEpochMilli()).commit()) {
            // SharedPreferences publishes in memory even when its disk commit fails.
            prefs.edit().putLong(key,old).apply()
            error("暂放设置未保存")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun MobileReviewDialog(state:SyncState,now:Instant,hintsEnabled:Boolean,onHintsChange:(Boolean)->Unit,
    onOpen:(ReviewAttention)->Unit,onAI:(String)->Unit,onDismiss:()->Unit) {
    val review=remember(state,now) {MobileReview.calculate(state,now)}
    var showCompleted by rememberSaveable {mutableStateOf(false)}
    var showAnalytics by rememberSaveable {mutableStateOf(false)}
    var copyStatus by remember {mutableStateOf("")}
    var copyMessage by remember {mutableStateOf<String?>(null)}
    var copyEvent by remember {mutableLongStateOf(0L)}
    val context=LocalContext.current
    val analytics=remember(state,now) {ReviewAnalytics.calculate(state,now)}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().testTag("review_dialog"),color=MaterialTheme.colorScheme.background) {
            Column {
                ParePageHeader("回看最近7天",onDismiss,backModifier=Modifier.testTag("review_close"),backLabel="关闭")
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    item {
                        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("你的实际投入",style=MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                ReviewStat("完成任务","${review.completedTasks.size} 件",Modifier.weight(1f))
                                ReviewStat("自己专注","${review.focusMinutes} 分钟",Modifier.weight(1f))
                            }
                            Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.medium) {
                                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Text("等待结果 ${review.externalMinutes} 分钟",style=MaterialTheme.typography.titleSmall)
                                    Text("交给 AI 或别人后记录的等待时长，与自己的专注时间分开统计；不含预估时间。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if(review.blockerCounts.isNotEmpty()) item {
                        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("最近的卡点",style=MaterialTheme.typography.titleMedium)
                            val labels=mapOf("unclear" to "下一步不清楚","tooLarge" to "任务太大","lowEnergy" to "精力不足","resistance" to "有压力或抗拒")
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                review.blockerCounts.entries.sortedByDescending {it.value}.forEach {entry ->
                                    Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.small) {
                                        Text("${labels[entry.key]} · ${entry.value}次",Modifier.padding(horizontal=12.dp,vertical=8.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSecondaryContainer)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        TextButton(onClick={showAnalytics=!showAnalytics},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("review_analytics_toggle")) {Text(if(showAnalytics) "收起8周趋势与估时校准" else "查看8周趋势与估时校准")}
                        if(showAnalytics) Column(verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.testTag("review_analytics")) {
                            Text("按规划时区 ${analytics.zone} 的自然周统计；本周未结束。只使用已有记录，不含已删除或冲突条目。",style=MaterialTheme.typography.bodySmall)
                            if(!analytics.hasRecords) Text("这8周还没有完成或投入记录。",style=MaterialTheme.typography.bodyMedium)
                            analytics.weeks.forEach {week->
                                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Text("${week.start}${if(week.current) " · 本周" else "起"}",style=MaterialTheme.typography.labelLarge)
                                    Text("完成 ${week.completed} 件 · 专注 ${week.focusMinutes} 分钟 · 等待 ${week.waitingMinutes} 分钟",style=MaterialTheme.typography.bodySmall)
                                    val max=analytics.weeks.maxOf {it.focusMinutes}.coerceAtLeast(1)
                                    LinearProgressIndicator(progress={week.focusMinutes.toFloat()/max.toFloat()},modifier=Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text("8周完成任务的象限分布",style=MaterialTheme.typography.titleSmall)
                            val names=listOf("重要且紧急","重要不紧急","紧急不重要","不紧急不重要")
                            analytics.quadrantCompletions.forEach {(quadrant,count)->Text("${names[quadrant]} · $count 件",style=MaterialTheme.typography.bodyMedium)}
                            val c=analytics.calibration
                            Text("估时校准",style=MaterialTheme.typography.titleSmall)
                            Text(if(c==null) "尚无同时填写预计与实际分钟的已完成任务，不作推算。" else "${c.pairs} 件完整样本：预计 ${c.estimatedMinutes} 分钟，实际 ${c.actualMinutes} 分钟；实际/预计 ${String.format(java.util.Locale.ROOT,"%.2f",c.ratio)} 倍。",style=MaterialTheme.typography.bodyMedium)
                        }
                        OutlinedButton(shape=MaterialTheme.shapes.medium,onClick={
                            copyMessage=null
                            val text=ReviewAnalytics.export(state,now)
                            copyStatus=try {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Step 本地回顾",text))
                                copyEvent++; copyMessage="已复制汇总；不含任务标题或 AI 原始记录"
                                ""
                            } catch(_:Exception) {"复制失败，请重试"}
                        },modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("review_copy")) {Text("复制本地回顾")}
                        if(copyStatus.isNotEmpty())Text(copyStatus,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("review_copy_status"))
                    }
                    item {Text("值得回看",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=8.dp))}
                    if(review.attention.isEmpty()) item {
                        Surface(color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.large) {
                            Text("暂时没有需要特别回看的事项。",Modifier.fillMaxWidth().padding(16.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(review.attention,key={it.key}) {hint ->
                        Surface(color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.large) {
                            Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick={onOpen(hint)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),contentPadding=PaddingValues(0.dp),
                                    colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onSurface)) {
                                    Text(hint.title,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                                    Spacer(Modifier.width(8.dp))
                                    PareIcon(PareIcons.Next,null,modifier=Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(hint.reason,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                if(hint.kind=="task") TextButton(onClick={onAI(hint.id)},modifier=Modifier.heightIn(min=48.dp),contentPadding=PaddingValues(0.dp)) {Text("让 AI 帮我找下一步")}
                                else Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                    item {
                        TextButton(onClick={showCompleted=!showCompleted},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),contentPadding=PaddingValues(0.dp),
                            colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onSurface)) {
                            Text(if(showCompleted) "收起完成记录" else "查看完成记录",modifier=Modifier.weight(1f))
                            PareIcon(if(showCompleted) PareIcons.Up else PareIcons.Down,null,modifier=Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if(showCompleted) items(review.completedTasks,key={"completed:${it.id}"}) {
                        Surface(color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.medium) {
                            Text(it.title(),Modifier.fillMaxWidth().padding(16.dp),style=MaterialTheme.typography.bodyMedium)
                        }
                    }
                    item {
                        Surface(color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.large) {
                            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth().heightIn(min=48.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Text("首页轻提示",style=MaterialTheme.typography.titleSmall,modifier=Modifier.weight(1f))
                                    Switch(checked=hintsEnabled,onCheckedChange=onHintsChange,modifier=Modifier.testTag("hints_enabled"))
                                }
                                Text("仅在本机发现值得回看的事项时提示；不会后台调用 AI。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                ManagementSnackbar(copyMessage,copyEvent,{event -> if(copyEvent==event) copyMessage=null},"review_copy_status",Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}

@Composable private fun ReviewStat(label:String,value:String,modifier:Modifier=Modifier) {
    Surface(modifier=modifier,color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(label,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value,style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary)
        }
    }
}
