package com.pare.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

@Composable fun MobileReviewDialog(state:SyncState,now:Instant,hintsEnabled:Boolean,onHintsChange:(Boolean)->Unit,
    onOpen:(ReviewAttention)->Unit,onAI:(String)->Unit,onDismiss:()->Unit) {
    val review=remember(state,now) {MobileReview.calculate(state,now)}
    var showCompleted by remember {mutableStateOf(false)}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().testTag("review_dialog")) {
            Column {
                Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("近7天的推进",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                    TextButton(onClick=onDismiss,modifier=Modifier.heightIn(min=48.dp).testTag("review_close")) {Text("关闭")}
                }
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("完成 ${review.completedTasks.size} 件 · 专注 ${review.focusMinutes} 分钟",style=MaterialTheme.typography.titleMedium)
                        Text("外部推进记录 ${review.externalMinutes} 分钟，单独统计。只计算实际留下的记录，不含估时。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(review.blockerCounts.isNotEmpty()) item {
                        HorizontalDivider();Text("最近的卡点",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=16.dp))
                        val labels=mapOf("unclear" to "下一步不清楚","tooLarge" to "任务太大","lowEnergy" to "精力不足","resistance" to "有压力或抗拒")
                        Text(review.blockerCounts.entries.sortedByDescending {it.value}.joinToString(" · ") {"${labels[it.key]} ${it.value}次"},style=MaterialTheme.typography.bodyMedium)
                    }
                    item {HorizontalDivider();Text("值得回看",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=16.dp))}
                    if(review.attention.isEmpty()) item {Text("暂时没有需要特别回看的事项。",color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    items(review.attention,key={it.key}) {hint ->
                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick={onOpen(hint)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),contentPadding=PaddingValues(0.dp)) {Text(hint.title,modifier=Modifier.fillMaxWidth())}
                            Text(hint.reason,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(hint.kind=="task") TextButton(onClick={onAI(hint.id)},modifier=Modifier.heightIn(min=48.dp)) {Text("让 AI 帮我找下一步")}
                        }
                    }
                    item {
                        TextButton(onClick={showCompleted=!showCompleted},modifier=Modifier.heightIn(min=48.dp)) {Text(if(showCompleted) "收起完成记录" else "查看完成记录")}
                    }
                    if(showCompleted) items(review.completedTasks,key={"completed:${it.id}"}) {Text(it.title(),style=MaterialTheme.typography.bodyMedium)}
                    item {
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth().heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text("首页轻提示",style=MaterialTheme.typography.bodyLarge,modifier=Modifier.weight(1f))
                            Switch(checked=hintsEnabled,onCheckedChange=onHintsChange,modifier=Modifier.testTag("hints_enabled"))
                        }
                        Text("仅在本机发现值得回看的事项时提示；不会后台调用 AI。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
