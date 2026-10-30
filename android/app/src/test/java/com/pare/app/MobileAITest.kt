package com.pare.app

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Fictional fixtures only. Network tests inject transport and cannot call a model. */
class MobileAITest {
    private val now=Instant.parse("2026-10-30T00:00:00Z")
    private val taskId="00000000-0000-4000-8000-000000000002"
    private val ideaId="00000000-0000-4000-8000-000000000001"
    private fun state():SyncState {
        val task=Entity("task",taskId,ContentActions.save(null,"task","Fictional garden sketch","Square bed",1,taskId,now,nextAction="Find paper"))
        val idea=Entity("idea",ideaId,ContentActions.save(null,"idea","Private garden idea","Do not send by default",1,ideaId,now).let {p ->
            JsonObject(p.toMutableMap().apply {put("content",JsonObject(p.getValue("content").jsonObject.toMutableMap().apply {put("linkedTaskIDs",JsonArray(listOf(JsonPrimitive(taskId))))}))})})
        return SyncState(device="fictional-device",items=mapOf(task.key to task,idea.key to idea))
    }
    private fun change(s:SyncState,field:String,value:JsonElement):SyncState {
        val t=s.items.getValue("task:$taskId")
        return StateMachine.edit(s,"task",taskId,JsonObject(t.payload!!.toMutableMap().apply {put("content",JsonObject(t.content()!!.toMutableMap().apply {put(field,value)}))}))
    }
    private fun proposal(input:MobileAIRequest)=MobileAIProposal(input.id,taskId,"Draw one side","Stop when one line exists",3,"Uses the selected sketch task")
    private fun envelope(content:String,finish:String="stop")=buildJsonObject {put("choices",buildJsonArray {add(buildJsonObject {put("finish_reason",finish);put("message",buildJsonObject {put("content",content)})})})}.toString()

    @Test fun previewIsOptInAndMatchesSentUserMessage() {
        val s=state();val input=MobileAIContext.prepare(s,taskId,MobileAIOptions(),emptyList())
        assertTrue(input.preview.contains("Fictional garden sketch"))
        assertTrue(input.preview.contains("Square bed"))
        assertFalse(input.preview.contains("Private garden idea"))
        val body=MobileAIClient.body(MobileAIConfig("https://model.invalid/v1/chat/completions","fictional-model"),input)
        val messages=wire.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
        assertEquals(input.preview,messages[1].jsonObject.getValue("content").jsonPrimitive.content)
        val allowed=MobileAIContext.prepare(s,taskId,MobileAIOptions(ideas=true),emptyList())
        assertTrue(allowed.preview.contains("Private garden idea"))
    }
    @Test fun historicalTaskFieldsRequireSeparateConsent() {
        val s=change(state(),"blockerEntries",buildJsonArray {add(buildJsonObject {put("reason","lowEnergy");put("at",now.toString());put("id",uid())})})
        assertFalse(MobileAIContext.prepare(s,taskId,MobileAIOptions(),emptyList()).preview.contains("lowEnergy"))
        assertTrue(MobileAIContext.prepare(s,taskId,MobileAIOptions(history=true),emptyList()).preview.contains("lowEnergy"))
    }
    @Test fun oversizedSelectedContextStopsBeforeTransport() {
        val s=change(state(),"note",JsonPrimitive("文".repeat(16000)))
        assertThrows(MobileAIException::class.java) {MobileAIContext.prepare(s,taskId,MobileAIOptions(),emptyList())}
    }
    @Test fun endpointRejectsCredentialAndRedirectLeakVectors() {
        for(url in listOf("http://model.invalid/v1","https://user:secret@model.invalid/v1","https://model.invalid/v1?key=secret","https://model.invalid/v1#secret","file:///tmp/key")) {
            assertThrows(MobileAIException::class.java) {MobileAIConfig(url,"model").validated()}
        }
        assertEquals("https://model.invalid/v1/chat/completions",MobileAIConfig("https://model.invalid/v1/chat/completions","model").validated().endpoint)
    }
    @Test fun validatesFencedJsonAndRejectsWrongTaskOrUnboundedAdvice() {
        val input=MobileAIContext.prepare(state(),taskId,MobileAIOptions(),emptyList())
        val good=proposal(input)
        assertEquals(good,MobileAIClient.decode(envelope("```json\n${wire.encodeToString(good)}\n```"),input))
        for(bad in listOf(good.copy(taskId=ideaId),good.copy(requestId="unrelated"),good.copy(minutes=90),good.copy(nextAction=" "),good.copy(stopCriterion=""))) {
            assertThrows(MobileAIException::class.java) {MobileAIClient.decode(envelope(wire.encodeToString(bad)),input)}
        }
        assertThrows(MobileAIException::class.java) {MobileAIClient.decode(envelope(wire.encodeToString(good),"length"),input)}
    }
    @Test fun adoptionRequiresUnchangedEvidenceAndPreservesEstimate() {
        val s=change(state(),"estimatedMinutes",JsonPrimitive(50));val input=MobileAIContext.prepare(s,taskId,MobileAIOptions(),emptyList())
        val adopted=MobileAIActions.adopt(s,input,proposal(input),now)
        val c=adopted.items.getValue("task:$taskId").content()!!
        assertEquals("Draw one side",c.getValue("nextAction").jsonPrimitive.content)
        assertEquals(50,c.getValue("estimatedMinutes").jsonPrimitive.int)
        assertThrows(MobileAIException::class.java) {MobileAIActions.adopt(change(s,"text",JsonPrimitive("Different task")),input,proposal(input),now)}
        assertThrows(MobileAIException::class.java) {MobileAIActions.adopt(s.copy(owner="other"),input,proposal(input),now)}
        assertThrows(MobileAIException::class.java) {MobileAIActions.adopt(change(s,"completed",JsonPrimitive(true)),input,proposal(input),now)}
        PayloadValidation.validate("task",taskId,adopted.items.getValue("task:$taskId").payload!!)
    }
    @Test fun undoRestoresOnlyAdoptedFieldsAndRejectsLaterNextStepChanges() {
        val s=state();val input=MobileAIContext.prepare(s,taskId,MobileAIOptions(),emptyList());val p=proposal(input)
        val receipt=MobileAIUndo.from(input,p,now)
        val adopted=MobileAIActions.adopt(s,input,p,now)
        val concurrent=change(adopted,"note",JsonPrimitive("Later independent note"))
        val restored=MobileAIActions.undo(concurrent,receipt)
        val c=restored.items.getValue("task:$taskId").content()!!
        assertEquals("Find paper",c.getValue("nextAction").jsonPrimitive.content)
        assertEquals("Later independent note",c.getValue("note").jsonPrimitive.content)
        assertFalse(c.containsKey("definitionOfDone"))
        assertThrows(MobileAIException::class.java) {MobileAIActions.undo(change(adopted,"nextAction",JsonPrimitive("New independent step")),receipt)}
    }
    @Test fun transportFailureIsRedactedAndNeverRetried() {
        val input=MobileAIContext.prepare(state(),taskId,MobileAIOptions(),emptyList());var calls=0
        val client=MobileAIClient(MobileAITransport {_,_,_->calls++;throw IllegalStateException("secret-key private URL")})
        val thrown=assertThrows(MobileAIException::class.java) {client.generate(MobileAIConfig("https://model.invalid/v1/chat/completions","model"),"secret-key",input)}
        assertEquals(1,calls);assertFalse(thrown.message!!.contains("secret-key"));assertNull(thrown.cause)
    }
    @Test fun httpRedirectAndOversizedResponseAreNotAccepted() {
        val input=MobileAIContext.prepare(state(),taskId,MobileAIOptions(),emptyList())
        for(reply in listOf(MobileAIResponse(302,"redirect"),MobileAIResponse(200,"x".repeat(128001)))) {
            var calls=0;val client=MobileAIClient(MobileAITransport {_,_,_->calls++;reply})
            assertThrows(MobileAIException::class.java) {client.generate(MobileAIConfig("https://model.invalid/v1/chat/completions","model"),"fake-key",input)}
            assertEquals(1,calls)
        }
    }
    @Test fun successfulTransportRecordsBoundedRawInputAndOutputWithoutHeaders() {
        val input=MobileAIContext.prepare(state(),taskId,MobileAIOptions(),emptyList());val p=proposal(input)
        var trace:MobileAITrace?=null
        val response=envelope(wire.encodeToString(p))
        val client=MobileAIClient(MobileAITransport {config,key,body ->
            assertEquals("model",config.model);assertEquals("fake-key",key)
            assertTrue(body.contains("Fictional garden sketch"));MobileAIResponse(200,response)
        })
        val result=client.generate(MobileAIConfig("https://model.invalid/v1/chat/completions","model"),"fake-key",input) {trace=it}
        assertEquals(p,result);assertTrue(trace!!.success)
        assertEquals(response,trace!!.output);assertFalse(trace!!.input.contains("fake-key"))
    }
    @Test fun localTracesStripCredentialsAndBoundHistory() {
        val trace=MobileAITrace("id",taskId,now.toString(),"input fake-key","Authorization: Bearer fake-key",true)
        val redacted=trace.redacted("fake-key")
        assertFalse(redacted.input.contains("fake-key"));assertFalse(redacted.output.contains("fake-key"));assertFalse(redacted.output.contains("Authorization"))
        val bounded=MobileAIHistory.append((1..20).map {trace.copy(id="$it")},redacted)
        assertTrue(bounded.size<=12);assertEquals("id",bounded.last().id)
        assertTrue(MobileAIHistory.remove(bounded,"id").none {it.id=="id"})
    }
    @Test fun ignoresUnrelatedGoalsExpiredPreferencesAndOtherTaskHistory() {
        var s=state()
        val goal=Entity("goal","fictional-goal",buildJsonObject {put("content",buildJsonObject {put("id","fictional-goal");put("title","Unrelated goal");put("status","active");put("taskIDs",buildJsonArray {add("other-task")})})})
        val profile=Entity("profile","profile",buildJsonObject {put("content",buildJsonObject {
            put("confirmedPreferences",buildJsonArray {
                add(buildJsonObject {put("id","a");put("text","Expired preference");put("expiresAt","2000-01-01T00:00:00Z")})
                add(buildJsonObject {put("id","b");put("text","Unrelated preference");put("ideaID","other-idea")})
                add(buildJsonObject {put("id","c");put("text","Approved short start")})
            })
        })})
        s=s.copy(items=s.items+(goal.key to goal)+(profile.key to profile))
        val traces=listOf(MobileAITrace("old","other-task",now.toString(),"Private other request","Private other output",true))
        val preview=MobileAIContext.prepare(s,taskId,MobileAIOptions(goals=true,preferences=true,history=true),traces,now).preview
        assertTrue(preview.contains("Approved short start"))
        for(excluded in listOf("Unrelated goal","Expired preference","Unrelated preference","Private other")) assertFalse(preview.contains(excluded))
    }
    @Test fun progressBetweenPreviewAndAdoptionCannotBeLostByUndo() {
        val s=state();val input=MobileAIContext.prepare(s,taskId,MobileAIOptions(),emptyList())
        val progressed=change(s,"lastProgressAt",JsonPrimitive(now.plusSeconds(30).toString()))
        assertThrows(MobileAIException::class.java) {MobileAIActions.adopt(progressed,input,proposal(input),now.plusSeconds(60))}
    }
    @Test fun rejectsDuplicateAndExtraProposalFields() {
        val input=MobileAIContext.prepare(state(),taskId,MobileAIOptions(),emptyList());val json=wire.encodeToString(proposal(input))
        for(raw in listOf(json.dropLast(1)+",\"minutes\":3}",json.dropLast(1)+",\"runCommand\":\"anything\"}")) {
            assertThrows(MobileAIException::class.java) {MobileAIClient.decode(envelope(raw),input)}
        }
    }
}
