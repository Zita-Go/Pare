package com.pare.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    var showCompleted by remember {mutableStateOf(false)}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().testTag("review_dialog"),color=MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(start=20.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("近7天的推进",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                    TextButton(onClick=onDismiss,modifier=Modifier.heightIn(min=48.dp).testTag("review_close")) {Text("关闭")}
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    item {
                        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("你的实际投入",style=MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                ReviewStat("完成任务","${review.completedTasks.size} 件",Modifier.weight(1f))
                                ReviewStat("个人专注","${review.focusMinutes} 分钟",Modifier.weight(1f))
                            }
                            Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.medium) {
                                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Text("外部推进记录 ${review.externalMinutes} 分钟",style=MaterialTheme.typography.titleSmall)
                                    Text("与个人专注分开统计；只计算实际记录，不含估时。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    Icon(Icons.Default.KeyboardArrowRight,null,modifier=Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Icon(if(showCompleted) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,null,modifier=Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
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
