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
 @Test fun taskDetailsCanBeChangedAndClearedWithoutLosingUnknownFields() {
  val original=Entity("task","id",wire.parseToJsonElement("""{"lifecycle":"active","futureEnvelope":{"keep":true},"content":{"id":"id","text":"old","createdAt":"2026-10-30T00:00:00Z","quadrant":1,"completed":false,"estimatedMinutes":20,"nextAction":"old step","futureContent":[1,2]}}""").jsonObject)
  val changed=ContentActions.save(original,"task","new","note",2,"id",Instant.now(),estimatedMinutes=45,nextAction="new step")
  assertEquals(original.payload!!["futureEnvelope"],changed["futureEnvelope"])
  assertEquals(original.content()!!["futureContent"],changed["content"]!!.jsonObject["futureContent"])
  assertEquals(45,changed["content"]!!.jsonObject["estimatedMinutes"]!!.jsonPrimitive.int)
  assertEquals("new step",changed["content"]!!.jsonObject["nextAction"]!!.jsonPrimitive.content)
  val cleared=ContentActions.save(original,"task","new","",1,"id",Instant.now(),estimatedMinutes=null,nextAction=null)["content"]!!.jsonObject
  assertFalse(cleared.containsKey("estimatedMinutes"));assertFalse(cleared.containsKey("nextAction"))
  val preserved=ContentActions.save(original,"task","new","",1,"id",Instant.now())["content"]!!.jsonObject
  assertEquals(20,preserved["estimatedMinutes"]!!.jsonPrimitive.int)
  assertEquals("old step",preserved["nextAction"]!!.jsonPrimitive.content)
 }
}
