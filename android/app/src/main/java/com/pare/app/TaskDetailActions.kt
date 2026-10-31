package com.pare.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Secondary tools live with the task, rather than competing in its quick menu. */
@Composable internal fun TaskDetailActions(task:Entity,changed:Boolean,busy:Boolean,inToday:Boolean,win:Boolean,
    running:Boolean,hasOrigin:Boolean,onAction:(String)->Unit) {
    var more by rememberSaveable(task.id) {mutableStateOf(false)}
    val prefix=if(changed) "保存后" else ""
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(changed)Text("先保存这份修改，再继续下方操作。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(!task.completed()) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={onAction("schedule")},enabled=!busy,shape=MaterialTheme.shapes.small,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("schedule_${task.id}")) {Text("${prefix}安排时间")}
                OutlinedButton(onClick={onAction("run")},enabled=!busy,shape=MaterialTheme.shapes.small,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("run_${task.id}")) {Text(prefix+if(running) "查看进度" else "开始专注")}
            }
            TextButton(onClick={onAction("complete")},enabled=!busy,modifier=Modifier.heightIn(min=48.dp).testTag("detail_complete_${task.id}")) {Text("${prefix}标记完成")}
        } else TextButton(onClick={onAction("complete")},enabled=!busy,modifier=Modifier.heightIn(min=48.dp).testTag("detail_complete_${task.id}")) {Text("${prefix}标为未完成")}
        TextButton(onClick={more=!more},modifier=Modifier.heightIn(min=48.dp).testTag("task_tools_toggle")) {Text(if(more) "收起其他操作" else "AI、提醒与其他操作")}
        if(more) {
            val options=buildList {
                if(!task.completed()) {add(Triple("ai","AI 帮我找下一步","ai"));add(Triple("support","把下一步拆小一点","support"));add(Triple("remind","提醒我","remind"));add(Triple("plan","设置今天投入多久","plan"))}
                add(Triple("today",if(inToday) "移出今天" else "加入今天","today"))
                add(Triple("win",if(win) "取消今日重点" else "设为今日重点","win"))
                if(hasOrigin)add(Triple("origin","查看关联想法","origin"))
            }
            options.forEach {(action,label,tag) ->
                TextButton(onClick={onAction(action)},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("${tag}_${task.id}")) {Text(prefix+label,modifier=Modifier.fillMaxWidth())}
            }
        }
    }
}
