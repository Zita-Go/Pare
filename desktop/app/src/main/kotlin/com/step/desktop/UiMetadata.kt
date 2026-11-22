package com.step.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pare.app.*
import java.time.*
import java.time.format.DateTimeFormatter

internal class UiMetadataDraft(task:Entity,private val zone:ZoneId) {
    var baseline by mutableStateOf(DesktopMetadataBaseline.from(task))
    var estimate by mutableStateOf(task.content().number("estimatedMinutes")?.toString().orEmpty())
    var due by mutableStateOf(task.content().str("dueAt").let {runCatching {Instant.parse(it).atZone(zone).format(metadataFormat)}.getOrDefault("")})
    var actual by mutableStateOf(task.content().number("actualMinutes")?.toString().orEmpty())
    private var original=listOf(estimate,due,actual)
    val dirty get()=listOf(estimate,due,actual)!=original
    fun saved(task:Entity){baseline=DesktopMetadataBaseline.from(task);original=listOf(estimate,due,actual)}
}
private val metadataFormat=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
@Composable internal fun ColumnScope.TaskMetadataPanel(runtime:DesktopRuntime,task:Entity,draft:UiMetadataDraft,work:UiWork,onSaved:(Entity)->Unit) {
    SectionLabel("截止与投入","任务总估时与今天安排的时长分开记录。")
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Field(draft.estimate,{draft.estimate=it},"任务总预估（分钟）",Modifier.weight(1f),enabled=!work.busy)
        Field(draft.actual,{draft.actual=it},"累计实际投入（分钟）",Modifier.weight(1f),enabled=!work.busy)
    }
    DesktopDateTimeField("截止时间",draft.due,{draft.due=it},enabled=!work.busy)
    Text("截止表示最晚完成时间，提醒单独安排。校正实际投入会保留已记录的每段专注和等待。",style=MaterialTheme.typography.bodySmall,color=StepMuted)
    StepButton({
        val e=draft.estimate;val a=draft.actual;val d=draft.due;val expected=draft.baseline
        work.editSnapshot({state->state.items.getValue(task.key).let {draft.saved(it);onSaved(it)}}) {state->
            fun minutes(value:String)=value.trim().takeIf {it.isNotEmpty()}?.let {it.toIntOrNull()?:error("请填写整数分钟")}
            val zone=PreferencesActions.planningZone(state)
            val due=d.trim().takeIf {it.isNotEmpty()}?.let {LocalDateTime.parse(it,metadataFormat).atZone(zone).toInstant()}
            DesktopTaskMetadata.save(state,task.id,expected,minutes(e),due,minutes(a),Instant.now())
        }
    },enabled=!work.busy&&draft.dirty,modifier=Modifier.testTag("task_metadata_save")){Text("保存截止与投入")}
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DesktopDateTimeField(label:String,value:String,onChange:(String)->Unit,enabled:Boolean=true) {
    var calendar by remember {mutableStateOf(false)}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Field(value,onChange,"$label yyyy-MM-dd HH:mm（留空清除）",Modifier.weight(1f),enabled=enabled)
        StepOutlinedButton({calendar=true},enabled=enabled,modifier=Modifier.padding(top=23.dp)){Text("选择日期")}
    }
    if(calendar) {
        val initial=runCatching {LocalDateTime.parse(value,metadataFormat)}.getOrNull()
        val picker=rememberDatePickerState(initialSelectedDateMillis=(initial?.toLocalDate()?:LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest={calendar=false},confirmButton={StepButton({
            picker.selectedDateMillis?.let {millis->val date=Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate();onChange(date.atTime(initial?.toLocalTime()?:LocalTime.of(17,0)).format(metadataFormat))};calendar=false
        }){Text("选择")}},dismissButton={StepTextButton({calendar=false}){Text("取消")}}){DatePicker(picker)}
    }
}
