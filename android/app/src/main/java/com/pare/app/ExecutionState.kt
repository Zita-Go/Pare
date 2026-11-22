package com.pare.app

import kotlinx.serialization.Serializable
import java.util.UUID

/** Device-local execution metadata. Only finished history is written to shared task content. */
@Serializable data class ExecutionMoment(val wallMillis:Long,val elapsedMillis:Long,val bootId:String)
fun interface ExecutionClock {fun now():ExecutionMoment}
interface ExecutionPersistence {
    fun load():ExecutionSnapshot?
    fun save(snapshot:ExecutionSnapshot)
}
@Serializable data class ExecutionFinish(val endedAtMillis:Long,val minutes:Int)
@Serializable data class ExecutionRun(
    val id:String,
    val taskId:String,
    val startedAtMillis:Long,
    val accumulatedMillis:Long=0,
    val anchor:ExecutionMoment?=null,
    val targetMinutes:Int?=null,
    val nextCheckAtMillis:Long?=null,
    val deadlineAtMillis:Long?=null,
    val deadlineGeneration:Int=0,
    val paused:Boolean=false,
    val due:Boolean=false,
    val recoveryNotice:String?=null,
    val pendingFinish:ExecutionFinish?=null
) {
    fun elapsedMillis(now:ExecutionMoment):Long {
        val start=anchor
        val delta=if(!paused && start!=null && start.bootId==now.bootId && now.elapsedMillis>=start.elapsedMillis) now.elapsedMillis-start.elapsedMillis else 0L
        val total=accumulatedMillis+delta.coerceAtMost(Long.MAX_VALUE-accumulatedMillis)
        return targetMinutes?.let {total.coerceAtMost(it*60_000L)} ?: total
    }
    fun focusDeadlineMillis():Long?=deadlineAtMillis.takeIf {!paused || due}
    /** Rebase a monotonic deadline after wall-clock adjustment; late reads keep the same instant. */
    fun currentFocusDeadlineMillis(now:ExecutionMoment):Long? {
        if(pendingFinish!=null || (paused && !due)) return null
        val target=targetMinutes ?: return null
        if(due) return deadlineAtMillis
        val start=anchor ?: return null
        if(start.bootId!=now.bootId || now.elapsedMillis<start.elapsedMillis) return null
        val rawElapsed=accumulatedMillis+(now.elapsedMillis-start.elapsedMillis)
        return now.wallMillis+target*60_000L-rawElapsed
    }
}
@Serializable data class ExecutionSnapshot(
    val version:Int=1,
    val owner:String?=null,
    val device:String="",
    val focus:ExecutionRun?=null,
    val external:List<ExecutionRun> = emptyList()
) {
    fun runFor(taskId:String)=focus?.takeIf {it.taskId==taskId} ?: external.firstOrNull {it.taskId==taskId}
    /** Refuse a damaged local ledger before publishing it or overwriting its recoverable bytes. */
    fun validate() {
        try {
            require(version==1 && device.isNotBlank() && (owner==null || owner.isNotBlank()))
            val runs=listOfNotNull(focus)+external
            require(runs.map {it.id}.distinct().size==runs.size && runs.map {it.taskId}.distinct().size==runs.size)
            for(run in runs) {
                require(UUID.fromString(run.id).toString()==run.id && UUID.fromString(run.taskId).toString()==run.taskId)
                require(run.startedAtMillis>=0 && run.accumulatedMillis in 0..Long.MAX_VALUE-59_999 && run.deadlineGeneration>=0)
                require(run.targetMinutes==null || run.targetMinutes in 1..1440)
                require(run.paused || run.anchor!=null)
                run.anchor?.let {require(it.elapsedMillis>=0 && it.bootId.isNotBlank())}
                run.pendingFinish?.let {require(run.paused && it.minutes>0 && it.endedAtMillis>=run.startedAtMillis)}
            }
            require(focus==null || focus.targetMinutes!=null)
            require(external.all {it.targetMinutes==null && !it.due})
        } catch(error:Exception) {
            throw IllegalArgumentException("本机执行状态无法读取，原数据已保留，请勿清除应用数据",error)
        }
    }
}
enum class ExecutionAction { START_FOCUS,START_EXTERNAL,PAUSE,RESUME,FINISH,CANCEL,SET_CHECK }
data class ExecutionCommand(val taskId:String,val action:ExecutionAction,val minutes:Int?=null)
