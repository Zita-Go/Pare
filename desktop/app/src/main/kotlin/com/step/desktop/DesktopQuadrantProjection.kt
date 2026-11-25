package com.step.desktop

import com.pare.app.*
import java.time.Instant

/** Display projection; completed cards never become draggable/plannable actions. */
internal object DesktopQuadrantProjection {
    fun tasks(state:SyncState,quadrant:Int,now:Instant):List<Entity> {
        val day=TimelineDay.fromPlanId(ContentActions.planId(state,now))
        val completedToday=state.items.values.filter {task->
            task.kind=="task"&&!task.deleted&&task.content()!=null&&!LibrarySearch.archived(task)&&
                task.completed()&&(task.content().number("quadrant")?:1)==quadrant&&
                parseInstant(task.content().str("completedAt"))?.atZone(day.zone)?.toLocalDate()==day.date
        }.sortedWith(compareBy<Entity> {parseInstant(it.content().str("createdAt"))?:Instant.EPOCH}.thenBy {it.id})
        // The movement query deliberately remains unfinished-only. Visibility does not
        // write lifecycle/archive changes, touch plans, or make completed tasks actionable.
        return OrganizeActions.tasks(state,quadrant)+completedToday
    }
    private fun parseInstant(value:String):Instant? = runCatching {Instant.parse(value)}.getOrNull()
}
