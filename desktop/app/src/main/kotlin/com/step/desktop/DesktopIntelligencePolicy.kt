package com.step.desktop

import com.pare.app.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Path
import java.time.Instant
import java.security.MessageDigest

internal data class DesktopHint(val kind:String,val subjectId:String,val title:String,val reason:String,val evidence:String) {
    val key:String get()="$kind:$subjectId"
}
@Serializable internal data class DesktopHintMemory(
    val enabled:Boolean=true,
    val dailyCapacityMinutes:Int?=null,
    val lastShownAtMillis:Long?=null,
    val ignored:Map<String,String> = emptyMap(),
    val snoozedUntil:Map<String,Long> = emptyMap()
)

/** Uses recorded progress and explicit allocations, never inferred productivity or model calls. */
internal object DesktopHintPolicy {
    fun candidates(state:SyncState,planId:String,now:Instant,dailyCapacityMinutes:Int?=null):List<DesktopHint> {
        val review=MobileReview.calculate(state,now).attention.filter {entry->
            state.items["${entry.kind}:${entry.id}"]?.let {it.conflict==null&&it.conflictGroup.isEmpty()}==true
        }.map {DesktopHint(it.kind,it.id,it.title,it.reason,it.sourceAt)}
        val overload=if(dailyCapacityMinutes!=null&&dailyCapacityMinutes in 5..1440) {
            val day=TimelineDay.fromPlanId(planId)
            // Only the current day can interrupt the person with a planning-capacity hint.
            if(day.date==now.atZone(day.zone).toLocalDate()) {
                val overview=TodayOverviewProjection.project(state,day)
                val eligible=(overview.scheduled+overview.unscheduled).distinctBy {it.task.id}.filter {it.task.conflict==null&&it.task.conflictGroup.isEmpty()}
                val personal=eligible.map {it.task.id to TodayEffort.forTask(state,day,it.task.id)}.filter {it.second.kind=="personal"}
                val minutes=personal.sumOf {it.second.minutes?.toLong()?:0L}
                if(minutes>dailyCapacityMinutes)DesktopHint("overload",planId,"给今天留一点余地",
                    "已明确安排 $minutes 分钟个人投入，超过你设置的 $dailyCapacityMinutes 分钟提醒线；检查与等待未计入。",
                    fingerprint("$planId|$dailyCapacityMinutes|"+personal.sortedBy {it.first}.joinToString(","){"${it.first}:${it.second.minutes}"}))else null
            } else null
        } else null
        return listOfNotNull(overload)+review
    }
    fun select(candidates:List<DesktopHint>,memory:DesktopHintMemory,now:Instant):DesktopHint? {
        if(!memory.enabled)return null
        val at=now.toEpochMilli()
        memory.lastShownAtMillis?.let {last->if(at<last||at-last<24*3600_000L)return null}
        return candidates.firstOrNull {memory.ignored[it.key]!=it.evidence&&(memory.snoozedUntil[it.key]?:Long.MIN_VALUE)<=at}
    }
    fun shown(memory:DesktopHintMemory,now:Instant)=memory.copy(lastShownAtMillis=now.toEpochMilli())
    fun ignore(memory:DesktopHintMemory,hint:DesktopHint)=memory.copy(
        ignored=bounded(memory.ignored+(hint.key to hint.evidence)),snoozedUntil=memory.snoozedUntil-hint.key)
    fun snooze(memory:DesktopHintMemory,hint:DesktopHint,until:Instant)=memory.copy(
        snoozedUntil=bounded(memory.snoozedUntil+(hint.key to until.toEpochMilli())))
    private fun fingerprint(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private fun <T> bounded(values:Map<String,T>):Map<String,T> = values.entries.toList().takeLast(256).associate {it.key to it.value}
}
internal class DesktopHintStore(home:Path) {
    private val file=DesktopAtomicFile(home.resolve("intelligence-hints.json"),128*1024)
    fun load():DesktopHintMemory=file.read()?.let {validate(wire.decodeFromString<DesktopHintMemory>(it.toString(Charsets.UTF_8)))}?:DesktopHintMemory()
    fun save(value:DesktopHintMemory) {file.write(wire.encodeToString(validate(value)).toByteArray(Charsets.UTF_8))}
    private fun validate(value:DesktopHintMemory):DesktopHintMemory {
        require(value.dailyCapacityMinutes==null||value.dailyCapacityMinutes in 5..1440){"本机每日投入提醒线无效"}
        require(value.ignored.size<=256&&value.snoozedUntil.size<=256){"本机轻提示记录过多，原文件已保留"}
        require(value.ignored.all {it.key.length<=200&&it.value.length<=4096}&&value.snoozedUntil.keys.all {it.length<=200}){"本机轻提示记录无效，原文件已保留"}
        return value
    }
}
