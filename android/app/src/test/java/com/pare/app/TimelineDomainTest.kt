package com.pare.app

import java.time.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TimelineDomainTest {
    private val now = Instant.parse("2026-10-31T00:00:00Z")
    private val a = "00000000-0000-4000-8000-000000000001"
    private val b = "00000000-0000-4000-8000-000000000002"
    private val c = "00000000-0000-4000-8000-000000000003"
    private fun state(zone: String = "UTC"): SyncState = SyncState(items = (listOf(a,b,c).map { id ->
        Entity("task", id, ContentActions.save(null, "task", "Fictional $id", "", 1, id, now, estimatedMinutes = 90))
    } + Entity("profile", "profile", buildJsonObject {put("content", buildJsonObject {put("planningTimeZone", zone)})})).associateBy {it.key})
    private fun schedule(s: SyncState, id: String, plan: String, start: String, minutes: Int = 30) =
        TimelineActions.setSchedule(s, id, plan, Instant.parse(start), minutes, TimelineScheduleBaseline.from(s, plan, id))
    private fun editPlan(s: SyncState, planId: String, edit: (MutableMap<String, JsonElement>) -> Unit): SyncState {
        val old = s.items.getValue("day_plan:$planId")
        val content = old.content()!!.toMutableMap().apply(edit)
        return s.copy(items = s.items + (old.key to old.copy(payload = JsonObject(old.payload!!.toMutableMap().apply {put("content", JsonObject(content))}))))
    }
    private fun legacyFuture(): SyncState {
        val plan = "2026-10-31@UTC"
        return editPlan(schedule(state(), a, plan, "2026-10-31T09:00:00Z"), plan) {content ->
            content["task_plans"] = JsonArray(content.getValue("task_plans").jsonArray.map {entry ->
                JsonObject(entry.jsonObject.toMutableMap().apply {
                    put("scheduled_at", JsonPrimitive("2026-11-02T09:00:00Z"))
                    put("schedule_reminder_lead_minutes", JsonPrimitive(5))
                    put("future_entry", JsonPrimitive("keep"))
                    put("allocation", buildJsonObject {put("id", c);put("kind", "check");put("planned_minutes", 7)})
                })
            })
        }
    }
    private fun entry(s: SyncState, planId: String, taskId: String = a): JsonObject? =
        (s.items["day_plan:$planId"]?.content()?.get("task_plans") as? JsonArray).orEmpty()
            .map {it.jsonObject}.firstOrNull {it["task_id"]?.jsonPrimitive?.content == taskId}
    private fun laterLegacyCopy(s: SyncState, start: String = "2026-11-02T09:00:00Z"): SyncState {
        val source = s.items.getValue("day_plan:2026-10-31@UTC")
        val copy = source.copy(id = "2026-11-01@UTC", payload = buildJsonObject {put("content", JsonObject(source.content()!!.toMutableMap().apply {
            put("day_key", JsonPrimitive("2026-11-01"))
            put("task_plans", JsonArray(listOf(JsonObject(entry(s, source.id)!!.toMutableMap().apply {
                put("scheduled_at", JsonPrimitive(start)); put("scheduled_minutes", JsonPrimitive(45)); put("schedule_reminder_lead_minutes", JsonPrimitive(15))
            }))))
        }))})
        return s.copy(items = s.items + (copy.key to copy))
    }

    @Test fun elapsedAxisHandlesBothDstTransitionsAndRejectsInvalidWallTimes() {
        val s = state("America/New_York")
        val spring = TimelineDay.from(s, LocalDate.parse("2026-03-08"))
        assertEquals(1380, spring.minutes)
        assertThrows(IllegalArgumentException::class.java) {TimelineTime.resolveLocal(spring, 150)}
        val fall = TimelineDay.from(s, LocalDate.parse("2026-11-01"))
        assertEquals(1500, fall.minutes)
        assertThrows(IllegalArgumentException::class.java) {TimelineTime.resolveLocal(fall, 90)}
        val first = TimelineTime.resolveLocal(fall, 90, ZoneOffset.ofHours(-4))
        val second = TimelineTime.resolveLocal(fall, 90, ZoneOffset.ofHours(-5))
        assertEquals(3600L, Duration.between(first, second).seconds)
        assertEquals(first, TimelineTime.instantAt(fall, 90))
        assertEquals(second, TimelineTime.instantAt(fall, 150))
        assertEquals(75, TimelineTime.snapMinute(71.0))
        assertEquals(0, TimelineTime.snapMinute(-4.0))
    }

    @Test fun selectedDayAndPlanningZoneAreExplicit() {
        val s = state("Asia/Shanghai")
        val day = TimelineDay.from(s, LocalDate.parse("2026-11-04"))
        assertEquals("2026-11-04@Asia/Shanghai", day.planId)
        val saved = schedule(s, a, day.planId, "2026-11-03T16:15:00Z")
        assertEquals(15.0, TimelineProjection.project(saved, day).blocks.single().startMinute, 0.01)
        val baseline = TimelineScheduleBaseline.from(saved, day.planId, a)
        val changedZone = saved.copy(items = saved.items + state("UTC").items.filterKeys {it == "profile:profile"})
        assertThrows(StaleContentEditException::class.java) {
            TimelineActions.setSchedule(changedZone, a, day.planId, Instant.parse("2026-11-03T17:00:00Z"), 30, baseline)
        }
    }

    @Test fun crossMidnightProjectionClipsWithoutChangingActualEndOrDuplicatingCopies() {
        val day = "2026-10-31@UTC"
        val next = "2026-11-01@UTC"
        val original = schedule(state(), a, day, "2026-10-31T23:45:00Z", 60)
        val source = original.items.getValue("day_plan:$day")
        val copied = source.copy(id = next, payload = buildJsonObject {put("content", JsonObject(source.content()!!.toMutableMap().apply {put("day_key", JsonPrimitive("2026-11-01"))}))})
        val s = original.copy(items = original.items + (copied.key to copied))
        val before = TimelineProjection.project(s, TimelineDay.from(s, LocalDate.parse("2026-10-31"))).blocks.single()
        assertEquals(1440.0, before.endMinute, 0.01)
        val after = TimelineProjection.project(s, TimelineDay.from(s, LocalDate.parse("2026-11-01")))
        assertEquals(1, after.blocks.size)
        assertEquals(day, after.blocks.single().sourcePlanId)
        assertEquals(0.0, after.blocks.single().startMinute, 0.01)
        assertEquals(45.0, after.blocks.single().endMinute, 0.01)
        assertEquals(Instant.parse("2026-11-01T00:45:00Z"), after.blocks.single().end)
        assertTrue(after.unscheduled.isEmpty())
    }

    @Test fun overlapLanesAreDeterministicAndTouchingBlocksDoNotOverlap() {
        val plan = "2026-10-31@UTC"
        var s = schedule(state(), a, plan, "2026-10-31T09:00:00Z", 60)
        s = schedule(s, b, plan, "2026-10-31T09:30:00Z", 30)
        s = schedule(s, c, plan, "2026-10-31T10:00:00Z", 30)
        val blocks = TimelineProjection.project(s, TimelineDay.from(s, LocalDate.parse("2026-10-31"))).blocks
        assertEquals(listOf(0,1,0), blocks.map {it.lane})
        assertEquals(listOf(2,2,1), blocks.map {it.laneCount})
    }

    @Test fun copiedScheduleCannotResurrectAfterSourceMoveOrClear() {
        val plan = "2026-10-31@UTC"
        val copyId = "2026-11-01@UTC"
        val original = schedule(state(), a, plan, "2026-10-31T23:45:00Z", 60)
        val source = original.items.getValue("day_plan:$plan")
        val copied = source.copy(id = copyId, payload = buildJsonObject {put("content", JsonObject(source.content()!!.toMutableMap().apply {put("day_key", JsonPrimitive("2026-11-01"))}))})
        val s = original.copy(items = original.items + (copied.key to copied))
        val day = TimelineDay.from(s, LocalDate.parse("2026-11-01"))
        val cleared = TimelineActions.clearSchedule(s, a, plan, TimelineScheduleBaseline.from(s, plan, a))
        assertTrue(TimelineProjection.project(cleared, day).blocks.isEmpty())
        assertEquals(listOf(a), TimelineProjection.project(cleared, day).unscheduled.map {it.id})
        val moved = schedule(s, a, plan, "2026-10-31T20:00:00Z", 60)
        assertTrue(TimelineProjection.project(moved, day).blocks.isEmpty())
    }

    @Test fun copiedScheduleCannotExtendSourceAfterSameStartDurationShrinksToMidnight() {
        val plan = "2026-10-31@UTC"
        val copyId = "2026-11-01@UTC"
        val original = schedule(state(), a, plan, "2026-10-31T23:45:00Z", 60)
        val source = original.items.getValue("day_plan:$plan")
        val copied = source.copy(id = copyId, payload = buildJsonObject {put("content", JsonObject(source.content()!!.toMutableMap().apply {put("day_key", JsonPrimitive("2026-11-01"))}))})
        val withCopy = original.copy(items = original.items + (copied.key to copied))
        val resized = schedule(withCopy, a, plan, "2026-10-31T23:45:00Z", 15)
        val sourceDay = TimelineProjection.project(resized, TimelineDay.from(resized, LocalDate.parse("2026-10-31")))
        assertEquals(15, sourceDay.blocks.single().durationMinutes)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), sourceDay.blocks.single().end)
        assertTrue(TimelineProjection.project(resized, TimelineDay.from(resized, LocalDate.parse("2026-11-01"))).blocks.isEmpty())
    }

    @Test fun unrelatedStartDayPlanDoesNotHideLegacyFutureScheduleFromEarlierPlan() {
        val plan = "2026-10-31@UTC"
        val original = schedule(state(), a, plan, "2026-10-31T09:00:00Z", 60)
        val legacy = editPlan(original, plan) {content ->
            content["task_plans"] = JsonArray(content.getValue("task_plans").jsonArray.map {entry ->
                JsonObject(entry.jsonObject.toMutableMap().apply {put("scheduled_at", JsonPrimitive("2026-11-02T09:00:00Z"))})
            })
        }
        val withOtherTask = schedule(legacy, b, "2026-11-02@UTC", "2026-11-02T11:00:00Z")
        val projection = TimelineProjection.project(withOtherTask, TimelineDay.from(withOtherTask, LocalDate.parse("2026-11-02")))
        assertEquals(listOf(a, b), projection.blocks.map {it.task.id})
        assertEquals(plan, projection.blocks.first().sourcePlanId)
    }

    @Test fun legacyFutureMoveMigratesBothPlansAtomicallyPreservingUnrelatedTargetAndSourceFields() {
        val source = "2026-10-31@UTC"
        val target = "2026-11-02@UTC"
        val initial = legacyFuture()
        val baseline = TimelineScheduleBaseline.from(initial, source, a)
        val withB = schedule(initial, b, target, "2026-11-02T11:00:00Z")
        val at = Instant.parse("2026-11-02T09:30:00Z")
        val moved = TimelineActions.setSchedule(withB, a, source, at, 45, baseline)
        assertNull(entry(moved, source)!!["scheduled_at"])
        assertNull(entry(moved, source)!!["schedule_reminder_lead_minutes"])
        assertEquals(JsonPrimitive(at.toString()), entry(moved, target)!!["scheduled_at"])
        assertEquals(JsonPrimitive(45), entry(moved, target)!!["scheduled_minutes"])
        assertEquals(JsonPrimitive(5), entry(moved, target)!!["schedule_reminder_lead_minutes"])
        for(plan in listOf(source, target)) {
            assertEquals(entry(initial, source)!!["allocation"], entry(moved, plan)!!["allocation"])
            assertEquals(JsonPrimitive("keep"), entry(moved, plan)!!["future_entry"])
        }
        assertEquals(entry(withB, target, b), entry(moved, target, b))
        assertEquals(listOf(b, a), TimelineOrderBaseline.from(moved, target).orderedTaskIds)
        assertEquals(moved.items.getValue("day_plan:$source").group, moved.items.getValue("day_plan:$target").group)
        assertEquals(withB.items.getValue("task:$a"), moved.items.getValue("task:$a"))
        assertEquals(target, TimelineProjection.schedules(moved).first {it.task.id == a}.sourcePlanId)
        assertEquals(moved, TimelineActions.setSchedule(moved, a, source, at, 45, baseline))
        for(entity in moved.items.values.filter {it.kind == "day_plan"}) PayloadValidation.validate(entity.kind, entity.id, entity.payload!!)
    }

    @Test fun legacyFutureClearCreatesCanonicalUnscheduledEntryAndIsIdempotent() {
        val source = "2026-10-31@UTC"
        val target = "2026-11-02@UTC"
        val initial = legacyFuture()
        val baseline = TimelineScheduleBaseline.from(initial, source, a)
        val cleared = TimelineActions.clearSchedule(initial, a, source, baseline)
        assertNotNull(entry(cleared, target))
        assertNull(entry(cleared, target)!!["scheduled_at"])
        assertNull(entry(cleared, source)!!["scheduled_at"])
        assertEquals(JsonPrimitive("keep"), entry(cleared, target)!!["future_entry"])
        val projection = TimelineProjection.project(cleared, TimelineDay.from(cleared, LocalDate.parse("2026-11-02")))
        assertTrue(projection.blocks.isEmpty())
        assertEquals(listOf(a), projection.unscheduled.map {it.id})
        assertTrue(TimelineProjection.schedules(cleared).isEmpty())
        assertEquals(cleared, TimelineActions.clearSchedule(cleared, a, source, baseline))
    }

    @Test fun legacyMigrationRejectsConcurrentTargetTaskChangesWithoutTouchingEitherPlan() {
        val source = "2026-10-31@UTC"
        val target = "2026-11-02@UTC"
        val initial = legacyFuture()
        val baseline = TimelineScheduleBaseline.from(initial, source, a)
        val targetChanged = schedule(initial, a, target, "2026-11-02T12:00:00Z")
        assertThrows(StaleContentEditException::class.java) {
            TimelineActions.setSchedule(targetChanged, a, source, Instant.parse("2026-11-02T09:30:00Z"), 45, baseline)
        }
        assertThrows(StaleContentEditException::class.java) {TimelineActions.clearSchedule(targetChanged, a, source, baseline)}
        val targetBaseline = TimelineScheduleBaseline.from(targetChanged, source, a)
        val unknownEdit = editPlan(targetChanged, target) {content ->
            content["task_plans"] = JsonArray(content.getValue("task_plans").jsonArray.map {value ->
                JsonObject(value.jsonObject.toMutableMap().apply {put("new_remote_field", JsonPrimitive("changed"))})
            })
        }
        assertThrows(StaleContentEditException::class.java) {TimelineActions.clearSchedule(unknownEdit, a, source, targetBaseline)}
    }

    @Test fun legacyMigrationPreservesExistingTargetAllocationAndBothPlansUnknownFields() {
        val source = "2026-10-31@UTC"
        val target = "2026-11-02@UTC"
        var state = schedule(legacyFuture(), a, target, "2026-11-02T08:00:00Z")
        state = editPlan(state, target) {content ->
            content["future_plan"] = JsonPrimitive("target")
            content["task_plans"] = JsonArray(listOf(JsonObject(content.getValue("task_plans").jsonArray.single().jsonObject.toMutableMap().apply {
                put("allocation", buildJsonObject {put("id", b);put("kind", "personal");put("planned_minutes", 12);put("future_allocation", true)})
                put("target_field", JsonPrimitive("keep"))
            })))
        }
        val originalTarget = entry(state, target)!!
        val baseline = TimelineScheduleBaseline.from(state, source, a)
        val migrated = TimelineActions.setSchedule(state, a, source, Instant.parse("2026-11-02T09:15:00Z"), 45, baseline)
        assertEquals(originalTarget["allocation"], entry(migrated, target)!!["allocation"])
        assertEquals(JsonPrimitive("keep"), entry(migrated, target)!!["target_field"])
        assertEquals(JsonPrimitive("keep"), entry(migrated, target)!!["future_entry"])
        assertEquals(JsonPrimitive("target"), migrated.items.getValue("day_plan:$target").content()!!["future_plan"])
        assertEquals(entry(state, source)!!["allocation"], entry(migrated, source)!!["allocation"])
        assertThrows(StaleContentEditException::class.java) {
            TimelineActions.setSchedule(migrated, a, source, Instant.parse("2026-11-02T10:15:00Z"), 45, baseline)
        }
    }

    @Test fun legacyTargetDeletionOrConflictRejectsMoveClearAndSuccessfulRetry() {
        val source = "2026-10-31@UTC"
        val target = "2026-11-02@UTC"
        val initial = schedule(legacyFuture(), b, target, "2026-11-02T11:00:00Z")
        val baseline = TimelineScheduleBaseline.from(initial, source, a)
        val moved = TimelineActions.setSchedule(initial, a, source, Instant.parse("2026-11-02T09:00:00Z"), 30, baseline)
        for(state in listOf(initial, moved)) {
            val plan = state.items.getValue("day_plan:$target")
            for(blocked in listOf(plan.copy(payload = null, deleted = true), plan.copy(conflict = Remote("day_plan", target, 1, device = "fictional", sequence = 1)))) {
                val changed = state.copy(items = state.items + (plan.key to blocked))
                assertThrows(IllegalStateException::class.java) {
                    TimelineActions.setSchedule(changed, a, source, Instant.parse("2026-11-02T09:00:00Z"), 30, baseline)
                }
                assertThrows(IllegalStateException::class.java) {TimelineActions.clearSchedule(changed, a, source, baseline)}
            }
        }
    }

    @Test fun legacyClearLatestSnapshotClearsOtherOldCopies() {
        val source = "2026-10-31@UTC"
        val copyId = "2026-11-01@UTC"
        val initial = legacyFuture()
        val sourceEntity = initial.items.getValue("day_plan:$source")
        val copy = sourceEntity.copy(id = copyId, payload = buildJsonObject {put("content", JsonObject(sourceEntity.content()!!.toMutableMap().apply {put("day_key", JsonPrimitive("2026-11-01"))}))})
        val withCopy = initial.copy(items = initial.items + (copy.key to copy))
        val result = TimelineActions.clearSchedule(withCopy, a, copyId, TimelineScheduleBaseline.from(withCopy, copyId, a))
        assertTrue(TimelineProjection.schedules(result).isEmpty())
        assertNull(entry(result, source)!!["scheduled_at"])
        assertEquals(entry(withCopy, source)!!["future_entry"], entry(result, source)!!["future_entry"])
    }

    @Test fun sharedScheduleProjectionIncludesFutureLegacyAndUsesFullCanonicalAuthority() {
        val legacy = legacyFuture()
        val occurrence = TimelineProjection.schedules(legacy).single()
        assertEquals(Instant.parse("2026-11-02T09:00:00Z"), occurrence.start)
        assertEquals(5, occurrence.reminderLeadMinutes)
        val canonical = schedule(legacy, a, "2026-11-02@UTC", "2026-11-02T09:00:00Z", 15)
        val result = TimelineProjection.schedules(canonical).single()
        assertEquals("2026-11-02@UTC", result.sourcePlanId)
        assertEquals(15, result.durationMinutes)
    }

    @Test fun newestExplicitLegacySnapshotSuppliesDurationAndReminder() {
        val copied = laterLegacyCopy(legacyFuture())
        val occurrence = TimelineProjection.schedules(copied).single()
        assertEquals("2026-11-01@UTC", occurrence.sourcePlanId)
        assertEquals(45, occurrence.durationMinutes)
        assertEquals(15, occurrence.reminderLeadMinutes)
    }

    @Test fun legacyFutureDateMoveDoesNotLeaveOlderFutureOccurrence() {
        val copied = laterLegacyCopy(legacyFuture(), "2026-11-03T10:00:00Z")
        val occurrence = TimelineProjection.schedules(copied).single()
        assertEquals(Instant.parse("2026-11-03T10:00:00Z"), occurrence.start)
        assertTrue(TimelineProjection.project(copied, TimelineDay.from(copied, LocalDate.parse("2026-11-02"))).blocks.isEmpty())
    }

    @Test fun latestLegacySuppressedByCanonicalCannotReviveOlderFutureOccurrence() {
        val copied = laterLegacyCopy(legacyFuture(), "2026-11-03T10:00:00Z")
        val canonical = schedule(copied, a, "2026-11-03@UTC", "2026-11-03T10:00:00Z")
        val cleared = TimelineActions.clearSchedule(canonical, a, "2026-11-03@UTC", TimelineScheduleBaseline.from(canonical, "2026-11-03@UTC", a))
        assertTrue(TimelineProjection.schedules(cleared).isEmpty())
    }

    @Test fun explicitCanonicalSchedulesKeepIndependentDaysForSameTask() {
        val copied = laterLegacyCopy(legacyFuture(), "2026-11-03T10:00:00Z")
        val one = schedule(copied, a, "2026-11-03@UTC", "2026-11-03T10:00:00Z")
        val two = schedule(one, a, "2026-11-04@UTC", "2026-11-04T11:00:00Z")
        assertEquals(listOf("2026-11-03@UTC", "2026-11-04@UTC"), TimelineProjection.schedules(two).map {it.sourcePlanId})
    }

    @Test fun laterPlanWithoutTaskScheduleDoesNotImplyLegacyCancellation() {
        val legacy = legacyFuture()
        val later = schedule(legacy, b, "2026-11-01@UTC", "2026-11-01T09:00:00Z")
        assertEquals("2026-10-31@UTC", TimelineProjection.schedules(later).single {it.task.id == a}.sourcePlanId)
    }

    @Test fun migratingLatestLegacyAtomicallyClearsOlderDifferentDatePeersAndPreservesCanonicalDays() {
        val older = "2026-10-31@UTC"
        val source = "2026-11-01@UTC"
        val target = "2026-11-03@UTC"
        val independent = "2026-11-04@UTC"
        val initial = schedule(laterLegacyCopy(legacyFuture(), "2026-11-03T10:00:00Z"), a, independent, "2026-11-04T11:00:00Z")
        val baseline = TimelineScheduleBaseline.from(initial, source, a)
        val start = Instant.parse("2026-11-03T10:15:00Z")
        val moved = TimelineActions.setSchedule(initial, a, source, start, 45, baseline)
        assertEquals(listOf(target, independent), TimelineProjection.schedules(moved).map {it.sourcePlanId})
        for(id in listOf(older, source)) {
            assertNull(entry(moved, id)!!["scheduled_at"])
            assertNull(entry(moved, id)!!["scheduled_minutes"])
            assertNull(entry(moved, id)!!["schedule_reminder_lead_minutes"])
            assertEquals(entry(initial, id)!!["allocation"], entry(moved, id)!!["allocation"])
            assertEquals(entry(initial, id)!!["future_entry"], entry(moved, id)!!["future_entry"])
            assertEquals(TimelineOrderBaseline.from(initial, id).orderedTaskIds, TimelineOrderBaseline.from(moved, id).orderedTaskIds)
        }
        assertEquals(initial.items.getValue("day_plan:$independent"), moved.items.getValue("day_plan:$independent"))
        assertEquals(1, listOf(older, source, target).map {moved.items.getValue("day_plan:$it").group}.distinct().size)
        assertEquals(moved, TimelineActions.setSchedule(moved, a, source, start, 45, baseline))
        val cleared = TimelineActions.clearSchedule(initial, a, source, baseline)
        assertEquals(listOf(independent), TimelineProjection.schedules(cleared).map {it.sourcePlanId})
        assertEquals(cleared, TimelineActions.clearSchedule(cleared, a, source, baseline))
    }

    @Test fun legacyPeerScheduleChangeDeletionConflictAndNewLaterPeerRejectMigration() {
        val source = "2026-11-01@UTC"
        val older = "2026-10-31@UTC"
        val initial = laterLegacyCopy(legacyFuture(), "2026-11-03T10:00:00Z")
        val baseline = TimelineScheduleBaseline.from(initial, source, a)
        val changed = editPlan(initial, older) {content ->
            content["task_plans"] = JsonArray(listOf(JsonObject(entry(initial, older)!!.toMutableMap().apply {put("scheduled_minutes", JsonPrimitive(90))})))
        }
        val peer = initial.items.getValue("day_plan:$older")
        val deleted = initial.copy(items = initial.items + (peer.key to peer.copy(payload = null, deleted = true)))
        val conflict = initial.copy(items = initial.items + (peer.key to peer.copy(conflict = Remote("day_plan", older, 1, device = "fictional", sequence = 1))))
        val latest = initial.items.getValue("day_plan:$source").copy(id = "2026-11-02@UTC", payload = buildJsonObject {put("content", JsonObject(initial.items.getValue("day_plan:$source").content()!!.toMutableMap().apply {put("day_key", JsonPrimitive("2026-11-02"))}))})
        val newPeer = initial.copy(items = initial.items + (latest.key to latest))
        for(blocked in listOf(changed, deleted, conflict, newPeer)) {
            assertThrows(IllegalStateException::class.java) {TimelineActions.setSchedule(blocked, a, source, Instant.parse("2026-11-03T10:15:00Z"), 45, baseline)}
            assertThrows(IllegalStateException::class.java) {TimelineActions.clearSchedule(blocked, a, source, baseline)}
            assertNotNull(entry(blocked, source)!!["scheduled_at"])
            assertNull(blocked.items["day_plan:2026-11-03@UTC"])
        }
    }

    @Test fun migrationPreservesUnrelatedPeerChangesButRejectsInheritedGroupOverOneHundred() {
        val older = "2026-10-31@UTC"
        val source = "2026-11-01@UTC"
        val initial = laterLegacyCopy(legacyFuture(), "2026-11-03T10:00:00Z")
        val baseline = TimelineScheduleBaseline.from(initial, source, a)
        val unknownEdit = editPlan(initial, older) {content ->
            content["task_plans"] = JsonArray(listOf(JsonObject(entry(initial, older)!!.toMutableMap().apply {put("remote_unknown", JsonPrimitive("keep"))})))
        }
        val result = TimelineActions.clearSchedule(unknownEdit, a, source, baseline)
        assertEquals(JsonPrimitive("keep"), entry(result, older)!!["remote_unknown"])
        // The current migration touches three plans, but the source already belongs to a group
        // with 98 other dirty entities. Checking only the number of edited plans would miss this.
        val sourceEntity = initial.items.getValue("day_plan:$source")
        val extras = (1..98).associate {index ->
            val id = "10000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
            val entity = Entity("task", id, ContentActions.save(null, "task", "Fictional grouped task", "", 1, id, now), dirty = true, group = sourceEntity.group)
            entity.key to entity
        }
        val oversized = initial.copy(items = initial.items + extras)
        assertThrows(IllegalArgumentException::class.java) {TimelineActions.clearSchedule(oversized, a, source, baseline)}
        assertThrows(IllegalArgumentException::class.java) {TimelineActions.setSchedule(oversized, a, source, Instant.parse("2026-11-03T10:15:00Z"), 45, baseline)}
        assertNotNull(entry(oversized, older)!!["scheduled_at"])
        assertNotNull(entry(oversized, source)!!["scheduled_at"])
        assertNull(oversized.items["day_plan:2026-11-03@UTC"])
        val bounded = initial.copy(items = initial.items + extras.entries.take(97).associate {it.key to it.value})
        val exactlyOneHundred = TimelineActions.clearSchedule(bounded, a, source, baseline)
        assertEquals(100, StateMachine.prepare(exactlyOneHundred).pending!!.mutations.size)
    }

    @Test fun completedBlocksRemainVisibleButCannotBeMovedOrReturnedToUnscheduled() {
        val plan = "2026-10-31@UTC"
        var scheduled = schedule(state(), a, plan, "2026-10-31T09:00:00Z")
        scheduled = schedule(scheduled, b, plan, "2026-10-31T11:00:00Z")
        scheduled = TimelineActions.clearSchedule(scheduled, b, plan, TimelineScheduleBaseline.from(scheduled, plan, b))
        val completed = scheduled.copy(items = scheduled.items.mapValues {(_, entity) ->
            if (entity.kind != "task") entity else entity.copy(payload = JsonObject(entity.payload!!.toMutableMap().apply {
                put("content", JsonObject(entity.content()!!.toMutableMap().apply {put("completed", JsonPrimitive(true))}))
            }))
        })
        val projection = TimelineProjection.project(completed, TimelineDay.from(completed, LocalDate.parse("2026-10-31")))
        assertEquals(listOf(a), projection.blocks.map {it.task.id})
        assertTrue(projection.blocks.single().task.completed())
        assertTrue(projection.unscheduled.isEmpty())
        val baseline = TimelineScheduleBaseline.from(completed, plan, a)
        assertThrows(IllegalStateException::class.java) {
            TimelineActions.setSchedule(completed, a, plan, Instant.parse("2026-10-31T10:00:00Z"), 30, baseline)
        }
        assertThrows(IllegalStateException::class.java) {TimelineActions.clearSchedule(completed, a, plan, baseline)}
    }

    @Test fun scheduleAndClearPreserveAllocationEstimateUnknownsAndCancelScheduleReminder() {
        val plan = "2026-10-31@UTC"
        val s = schedule(state(), a, plan, "2026-10-31T09:00:00Z")
        val withFields = editPlan(s, plan) {content ->
            content["future"] = JsonPrimitive("keep")
            content["daily_win_task_id"] = JsonPrimitive(a)
            content["task_plans"] = JsonArray(content.getValue("task_plans").jsonArray.map {entry -> JsonObject(entry.jsonObject.toMutableMap().apply {
                put("future_entry", JsonPrimitive(7))
                put("allocation", buildJsonObject {put("id", c);put("kind", "check");put("planned_minutes", 8)})
            })})
        }
        val baseline = TimelineScheduleBaseline.from(withFields, plan, a)
        val moved = TimelineActions.setSchedule(withFields, a, plan, Instant.parse("2026-10-31T10:00:00Z"), 45, baseline, 5)
        val entry = moved.items.getValue("day_plan:$plan").content()!!.getValue("task_plans").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(8), entry.getValue("allocation").jsonObject["planned_minutes"])
        assertEquals(withFields.items.getValue("task:$a"), moved.items.getValue("task:$a"))
        assertEquals(1, ReminderPlanner.sources(moved, emptyList(), now).size)
        val cleared = TimelineActions.clearSchedule(moved, a, plan, TimelineScheduleBaseline.from(moved, plan, a))
        val content = cleared.items.getValue("day_plan:$plan").content()!!
        val clearEntry = content.getValue("task_plans").jsonArray.single().jsonObject
        assertNull(clearEntry["scheduled_at"])
        assertNull(clearEntry["scheduled_minutes"])
        assertNull(clearEntry["schedule_reminder_lead_minutes"])
        assertEquals(entry["allocation"], clearEntry["allocation"])
        assertEquals(JsonPrimitive(7), clearEntry["future_entry"])
        assertEquals(JsonPrimitive("keep"), content["future"])
        assertEquals(JsonPrimitive(a), content["daily_win_task_id"])
        assertTrue(ReminderPlanner.sources(cleared, emptyList(), now).isEmpty())
        assertEquals(listOf(a), TimelineProjection.project(cleared, TimelineDay.from(cleared, LocalDate.parse("2026-10-31"))).unscheduled.map {it.id})
        PayloadValidation.validate("day_plan", plan, cleared.items.getValue("day_plan:$plan").payload!!)
    }

    @Test fun staleScheduleConflictAndDeletionRejectEvenExactRetries() {
        val plan = "2026-10-31@UTC"
        val s = schedule(state(), a, plan, "2026-10-31T09:00:00Z")
        val baseline = TimelineScheduleBaseline.from(s, plan, a)
        val changed = schedule(s, a, plan, "2026-10-31T11:00:00Z")
        assertThrows(StaleContentEditException::class.java) {TimelineActions.setSchedule(changed, a, plan, Instant.parse("2026-10-31T10:00:00Z"), 30, baseline)}
        for(key in listOf("task:$a", "day_plan:$plan")) {
            val old = s.items.getValue(key)
            for(invalid in listOf(old.copy(deleted = true, payload = null), old.copy(conflict = Remote(old.kind, old.id, 1, device = "fictional", sequence = 1)))) {
                val blocked = s.copy(items = s.items + (key to invalid))
                assertThrows(IllegalStateException::class.java) {TimelineActions.setSchedule(blocked, a, plan, Instant.parse("2026-10-31T09:00:00Z"), 30, baseline)}
            }
        }
    }

    @Test fun unrelatedRemoteEntryEditsMergeAndExactRetryDoesNotDirtyAgain() {
        val plan = "2026-10-31@UTC"
        val s = schedule(state(), a, plan, "2026-10-31T09:00:00Z")
        val baseline = TimelineScheduleBaseline.from(s, plan, a)
        val concurrent = schedule(s, b, plan, "2026-10-31T12:00:00Z")
        val moved = TimelineActions.setSchedule(concurrent, a, plan, Instant.parse("2026-10-31T10:00:00Z"), 30, baseline)
        assertEquals(2, moved.items.getValue("day_plan:$plan").content()!!.getValue("task_plans").jsonArray.size)
        assertEquals(moved, TimelineActions.setSchedule(moved, a, plan, Instant.parse("2026-10-31T10:00:00Z"), 30, baseline))
    }

    @Test fun reminderEditsTaskRenamesAndWrongDayCannotBeSilentlyOverwritten() {
        val plan = "2026-10-31@UTC"
        val s = schedule(state(), a, plan, "2026-10-31T09:00:00Z")
        val baseline = TimelineScheduleBaseline.from(s, plan, a)
        val reminderEdit = TimelineActions.setSchedule(s, a, plan, Instant.parse("2026-10-31T09:00:00Z"), 30, baseline, 15)
        assertThrows(StaleContentEditException::class.java) {TimelineActions.setSchedule(reminderEdit, a, plan, Instant.parse("2026-10-31T10:00:00Z"), 30, baseline)}
        assertThrows(StaleContentEditException::class.java) {TimelineActions.clearSchedule(reminderEdit, a, plan, baseline)}
        val task = s.items.getValue("task:$a")
        val renamed = task.copy(payload = ContentActions.save(task, "task", "Renamed elsewhere", "", 1, a, now))
        val remoteRename = s.copy(items = s.items + (task.key to renamed))
        assertThrows(StaleContentEditException::class.java) {TimelineActions.setSchedule(remoteRename, a, plan, Instant.parse("2026-10-31T10:00:00Z"), 30, baseline)}
        assertThrows(IllegalArgumentException::class.java) {TimelineActions.setSchedule(s, a, plan, Instant.parse("2026-11-01T10:00:00Z"), 30, baseline)}
        assertThrows(IllegalArgumentException::class.java) {TimelineActions.setSchedule(s, a, plan, Instant.parse("2026-10-31T10:00:00Z"), 0, baseline)}
        assertThrows(IllegalArgumentException::class.java) {TimelineActions.setSchedule(s, a, plan, Instant.parse("2026-10-31T10:00:00Z"), 1441, baseline)}
    }

    @Test fun creationGroupsTaskAndPlanAndStableIdRetryCannotOverwriteAnotherDraft() {
        val s = state().copy(items = state().items.filterKeys {it != "task:$a"})
        val plan = "2026-11-02@UTC"
        val baseline = TimelineScheduleBaseline.from(s, plan, a)
        val at = Instant.parse("2026-11-02T09:00:00Z")
        val created = TimelineActions.createScheduledTask(s, a, "Draw one leaf", 1, plan, at, 30, baseline, now, "Pick a pencil")
        assertEquals(2, created.items.values.count {it.dirty})
        assertEquals(1, created.items.values.filter {it.dirty}.map {it.group}.distinct().size)
        assertEquals(2, StateMachine.prepare(created).pending!!.mutations.size)
        val reload = wire.decodeFromString<SyncState>(wire.encodeToString(created))
        assertEquals(reload, TimelineActions.createScheduledTask(reload, a, "Draw one leaf", 1, plan, at, 30, baseline, now.plusSeconds(60), "Pick a pencil"))
        assertThrows(IllegalStateException::class.java) {TimelineActions.createScheduledTask(created, a, "Different title", 1, plan, at, 30, baseline, now)}
        assertThrows(IllegalStateException::class.java) {TimelineActions.createScheduledTask(created, a, "Draw one leaf", 1, plan, at, 45, baseline, now, "Pick a pencil")}
        for(entity in created.items.values.filter {it.dirty}) PayloadValidation.validate(entity.kind, entity.id, entity.payload!!)
    }

    @Test fun reorderPreservesConcurrentAppendsButRejectsRemoteReorderAndRemoval() {
        val plan = "2026-10-31@UTC"
        var s = schedule(state(), a, plan, "2026-10-31T09:00:00Z")
        s = schedule(s, b, plan, "2026-10-31T10:00:00Z")
        val baseline = TimelineOrderBaseline.from(s, plan)
        val concurrent = schedule(s, c, plan, "2026-10-31T11:00:00Z")
        val reordered = TimelineActions.reorder(concurrent, plan, listOf(b,a), baseline)
        val ids = reordered.items.getValue("day_plan:$plan").content()!!.getValue("ordered_task_ids").jsonArray.map {it.jsonPrimitive.content}
        assertEquals(listOf(b,a,c), ids)
        assertEquals(concurrent.items.getValue("day_plan:$plan").content()!!["task_plans"], reordered.items.getValue("day_plan:$plan").content()!!["task_plans"])
        assertEquals(reordered, TimelineActions.reorder(reordered, plan, listOf(b,a), baseline))
        val remoteOrder = editPlan(concurrent, plan) {it["ordered_task_ids"] = JsonArray(listOf(c,a,b).map(::JsonPrimitive))}
        assertThrows(StaleContentEditException::class.java) {TimelineActions.reorder(remoteOrder, plan, listOf(b,a), baseline)}
        val removed = editPlan(s, plan) {it["ordered_task_ids"] = JsonArray(listOf(JsonPrimitive(a)))}
        assertThrows(StaleContentEditException::class.java) {TimelineActions.reorder(removed, plan, listOf(b,a), baseline)}
    }
}
