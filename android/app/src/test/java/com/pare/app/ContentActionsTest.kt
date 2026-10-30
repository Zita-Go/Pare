package com.pare.app
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*
import java.time.Instant
class ContentActionsTest {
 @Test fun taskWireHasMacRequiredFieldsAndNoDeviceOrTodayFlags() {
  val p=ContentActions.save(null,"task","Example","note",1,"id",Instant.parse("2026-10-30T00:00:00Z"))
  val c=p.getValue("content").jsonObject
  assertEquals(1,c.getValue("quadrant").jsonPrimitive.int)
  assertEquals(false,c.getValue("completed").jsonPrimitive.boolean)
  assertEquals("2026-10-30T00:00:00Z",c.getValue("createdAt").jsonPrimitive.content)
  assertFalse(c.containsKey("isToday"));assertFalse(c.containsKey("lastImplementationTriggeredAt"))
 }
 @Test fun editingPreservesMacOnlySharedContent() {
  val original=Entity("task","id",wire.parseToJsonElement("""{"lifecycle":"active","content":{"id":"id","text":"old","createdAt":"2026-10-30T00:00:00Z","quadrant":1,"completed":false,"implementationPlan":{"kind":"time","action":"open notebook"},"progressSessions":[{"id":"session"}]}}""").jsonObject)
  val changed=ContentActions.save(original,"task","new","note",2,"id",Instant.now()).getValue("content").jsonObject
  assertEquals(original.content()!!.getValue("progressSessions"),changed.getValue("progressSessions"))
  assertEquals("new",changed.getValue("text").jsonPrimitive.content)
 }
 @Test fun todayUsesAccountPlanningZoneAndDoesNotModifyPriorDate() {
  val state=SyncState(items=mapOf("profile:profile" to Entity("profile","profile",wire.parseToJsonElement("""{"content":{"planningTimeZone":"America/Los_Angeles"}}""").jsonObject)))
  assertEquals("2026-10-29@America/Los_Angeles",ContentActions.planId(state,Instant.parse("2026-10-30T00:00:00Z")))
 }
}
