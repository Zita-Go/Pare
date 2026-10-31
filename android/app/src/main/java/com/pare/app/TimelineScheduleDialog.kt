package com.pare.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Activity-retained form: failures and rotations keep both the draft and its original baseline. */
class TimelineScheduleModel : ViewModel() {
    var initialized by mutableStateOf(false); private set
    var expected: TimelineScheduleBaseline? by mutableStateOf(null); private set
    var day: TimelineDay? by mutableStateOf(null); private set
    var taskId by mutableStateOf(""); private set
    var isNewTask by mutableStateOf(false); private set
    var title by mutableStateOf("")
    var time by mutableStateOf("09:00")
    var minutes by mutableStateOf("30")
    var reminderEnabled by mutableStateOf(false)
    var reminderMinutes by mutableStateOf("0")
    var saving by mutableStateOf(false); private set
    var saved by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var lastAttemptWasUnschedule by mutableStateOf(false); private set
    private var titleFocusRequested = false
    // Preserve a selected occurrence in a repeated DST hour until the clock text is changed.
    private var initialTime: String? = null
    private var initialOffset: ZoneOffset? = null

    fun initialize(state: SyncState, day: TimelineDay, task: Entity?, start: Instant? = null, sourcePlanId: String = day.planId) {
        if (initialized) return
        initialized = true
        isNewTask = task == null
        taskId = task?.id ?: uid()
        title = task?.title().orEmpty()
        val baseline = TimelineScheduleBaseline.from(state, sourcePlanId, taskId)
        expected = baseline
        val scheduled = baseline.scheduledAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val sourceDay = TimelineDay.fromPlanId(sourcePlanId)
        // Old Mac records can store a future block inside an earlier plan. Keep the source
        // baseline for the atomic migration, but edit the actual occurrence's local date.
        val formDay = if (scheduled != null) TimelineDay(scheduled.atZone(sourceDay.zone).toLocalDate(), sourceDay.zone) else day
        this.day = formDay
        val chosen = start ?: scheduled ?: formDay.date.atTime(9, 0).atZone(formDay.zone).toInstant()
        val local = chosen.atZone(formDay.zone)
        time = local.format(DateTimeFormatter.ofPattern("HH:mm"))
        initialTime = time
        initialOffset = local.offset
        minutes = (baseline.scheduledMinutes ?: 30).toString()
        reminderEnabled = baseline.reminderLeadMinutes != null
        reminderMinutes = (baseline.reminderLeadMinutes ?: 0).toString()
    }

    fun startInstant(): Instant? {
        val selectedDay = day ?: return null
        val clockText = time.trim()
        if (!Regex("(?:[01]?\\d|2[0-3]):[0-5]\\d").matches(clockText)) return null
        val local = runCatching { LocalTime.parse(clockText, DateTimeFormatter.ofPattern("H:mm")) }.getOrNull() ?: return null
        return runCatching {
            TimelineTime.resolveLocal(selectedDay, local.hour * 60 + local.minute, initialOffset.takeIf { time == initialTime })
        }.getOrNull()
    }
    fun durationMinutes(): Int? = minutes.trim().toIntOrNull()?.takeIf { it in 1..1440 }
    fun reminderLeadMinutes(): Int? = if (reminderEnabled) reminderMinutes.trim().toIntOrNull()?.takeIf { it in 0..525600 } else null
    fun validationMessage(): String? = when {
        isNewTask && title.trim().isEmpty() -> "先写下要做的事"
        startInstant() == null -> "请填写有效的24小时制时间，例如 09:30；夏令时跳过或重复的时刻请重新选择。"
        durationMinutes() == null -> "时长请填写 1–1440 分钟"
        reminderEnabled && reminderLeadMinutes() == null -> "提醒提前量请填写 0–525600 分钟"
        else -> null
    }
    fun begin(): Boolean {
        if (saving || saved || validationMessage() != null) return false
        saving = true; error = null; lastAttemptWasUnschedule = false
        return true
    }
    fun beginUnschedule(): Boolean {
        if (saving || saved || isNewTask || expected?.scheduledAt == null) return false
        saving = true; error = null; lastAttemptWasUnschedule = true
        return true
    }
    fun finish(result: Result<Unit>) {
        saving = false
        saved = result.isSuccess
        error = result.exceptionOrNull()?.message ?: if (result.isFailure) "保存失败，草稿已保留，请重试。" else null
    }
    /** Retained across activity recreation so reopening a keyboard never steals an existing edit. */
    fun claimInitialTitleFocus(): Boolean {
        if (!isNewTask || titleFocusRequested) return false
        titleFocusRequested = true
        return true
    }
    fun clear() {
        if (saving) return
        initialized = false; expected = null; day = null; taskId = ""; isNewTask = false
        title = ""; time = "09:00"; minutes = "30"; reminderEnabled = false; reminderMinutes = "0"
        saved = false; error = null; initialTime = null; initialOffset = null
        lastAttemptWasUnschedule = false
        titleFocusRequested = false
    }
}

/** Pinned save and cancel actions remain accessible above the keyboard and with enlarged text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimelineScheduleDialog(
    model: TimelineScheduleModel,
    available: Boolean,
    onSave: () -> Unit,
    onUnschedule: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val day = model.day ?: return
    val enabled = available && !model.saving
    val start = model.startInstant()
    val duration = model.durationMinutes()
    Dialog(onDismissRequest = { if (!model.saving) onDismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        dismissOnBackPress = !model.saving, dismissOnClickOutside = !model.saving)) {
        val titleFocus = remember(model.taskId) { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(model.taskId, enabled) {
            if (enabled && model.isNewTask) {
                withFrameNanos { }
                if (model.claimInitialTitleFocus()) {
                    titleFocus.requestFocus()
                    keyboard?.show()
                }
            }
        }
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight().testTag("timeline_schedule_dialog"), color = MaterialTheme.colorScheme.surface) {
                Column {
                    Text(if (model.isNewTask) "安排一件事" else "调整时间", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (model.isNewTask) {
                            PareFormField(model.title, { model.title = it }, "要做什么？", enabled = enabled,
                                modifier = Modifier.fillMaxWidth().focusRequester(titleFocus).testTag("timeline_schedule_title"), minLines = 2)
                        } else {
                            Text(model.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("timeline_schedule_title"))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(day.date.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE")), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("timeline_schedule_date"))
                            Text(day.zone.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        PareFormField(model.time, { model.time = it }, "开始时间 · 24小时制，例如 09:30", enabled = enabled,
                            singleLine = true, isError = start == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth().testTag("timeline_schedule_time"))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PareFormField(model.minutes, { model.minutes = it }, "持续分钟数", enabled = enabled, singleLine = true,
                                isError = duration == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth().testTag("timeline_schedule_duration"))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(15, 30, 60, 90).forEach { amount ->
                                    FilterChip(selected = duration == amount, onClick = { model.minutes = amount.toString() }, enabled = enabled,
                                        label = { Text("$amount 分钟") }, modifier = Modifier.heightIn(min = 48.dp).testTag("timeline_duration_$amount"))
                                }
                            }
                            if (start != null && duration != null) {
                                val end = start.plusSeconds(duration * 60L).atZone(day.zone)
                                Text("结束于 ${end.format(DateTimeFormatter.ofPattern(if (end.toLocalDate() == day.date) "HH:mm" else "M月d日 HH:mm"))}",
                                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("timeline_schedule_end"))
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("提醒我", style = MaterialTheme.typography.bodyLarge)
                                    Text(if (model.reminderEnabled) "送达取决于下方手机通知设置" else "默认关闭", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = model.reminderEnabled, onCheckedChange = { model.reminderEnabled = it }, enabled = enabled,
                                    modifier = Modifier.testTag("timeline_schedule_reminder"))
                            }
                            if (model.reminderEnabled) {
                                PareFormField(model.reminderMinutes, { model.reminderMinutes = it }, "提前分钟数 · 0 表示开始时", enabled = enabled, singleLine = true,
                                    isError = model.reminderLeadMinutes() == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth().testTag("timeline_schedule_reminder_minutes"))
                                MobileNotificationSettings(Modifier.fillMaxWidth().testTag("timeline_schedule_notification_settings"))
                            }
                        }
                        if (onUnschedule != null && !model.isNewTask && model.expected?.scheduledAt != null) {
                            OutlinedButton(onClick = onUnschedule, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("timeline_unschedule"), shape = MaterialTheme.shapes.small) {
                                Text(if (model.error != null && model.lastAttemptWasUnschedule) "重试移回待安排" else "移回待安排")
                            }
                            Text("保留任务、今日顺序和推进方式。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val validation = model.validationMessage()
                        if (validation != null) Text(validation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val error = if (!available) "任务已完成、归档、存在冲突或不可用。草稿已保留。" else model.error
                    val recovery = when {
                        !available -> "请取消后查看最新任务状态；取消会放弃当前草稿。"
                        model.lastAttemptWasUnschedule -> "可再次点击“移回待安排”重试。若内容已更新，请取消后重新打开；取消会放弃当前草稿。"
                        else -> "保存失败可重试。若内容已更新，请取消后重新打开以读取最新安排；取消会放弃当前草稿。"
                    }
                    if (error != null) Text("$error\n\n$recovery",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().heightIn(max = 120.dp).clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.errorContainer).verticalScroll(rememberScrollState()).padding(12.dp)
                            .testTag("timeline_schedule_error").semantics { liveRegion = LiveRegionMode.Polite })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = onDismiss, enabled = !model.saving, modifier = Modifier.heightIn(min = 48.dp).testTag("timeline_schedule_cancel")) { Text("取消") }
                        Button(onClick = onSave, enabled = enabled && model.validationMessage() == null,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("timeline_schedule_save"), shape = MaterialTheme.shapes.small) {
                            Text(if (model.saving) "正在保存…" else if (model.error != null && !model.lastAttemptWasUnschedule) "重试保存" else "保存")
                        }
                    }
                }
            }
        }
    }
}
