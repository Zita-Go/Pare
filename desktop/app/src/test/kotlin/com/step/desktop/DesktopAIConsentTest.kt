package com.step.desktop

import com.pare.app.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class DesktopAIConsentTest {
    private fun state(background:String="虚构背景：希望从小步骤开始"):SyncState = PreferencesActions.saveProfile(SyncState(device="fixture"),ProfileDraft(background=background),ProfileBaseline.from(null))
    private val feedback=DesktopAIFeedback("feedback-1","request-1","task-1","too_hard","虚构反馈", "2026-11-22T00:00:00Z")
    private val base="""{"requestId":"request-2","evidence":{"task":{"text":"虚构任务"}}}"""

    @Test fun backgroundAndFeedbackNeverEnterWithoutSeparateConsent() {
        val snapshot=DesktopAIConsent.prepare(base,state(),DesktopAIContext(feedback=listOf(feedback)),"task-1",false,false)
        assertEquals(base,snapshot.preview)
        assertFalse(snapshot.preview.contains("虚构背景"))
        assertFalse(snapshot.preview.contains("虚构反馈"))
    }

    @Test fun explicitContextPreservesEvidenceAndOnlyUsesThisSubjectsRecentFeedback() {
        val context=DesktopAIContext(feedback=listOf(feedback,feedback.copy(id="unrelated",subjectId="other",comment="不允许的反馈")))
        val snapshot=DesktopAIConsent.prepare(base,state(),context,"task-1",true,true)
        assertTrue(snapshot.preview.contains("虚构背景"))
        assertTrue(snapshot.preview.contains("虚构反馈"))
        assertFalse(snapshot.preview.contains("不允许的反馈"))
        val evidence=wire.parseToJsonElement(snapshot.preview).jsonObject.getValue("evidence").jsonObject
        assertEquals(wire.parseToJsonElement(base).jsonObject.getValue("evidence").jsonObject["task"],evidence["task"])
        assertNotNull(evidence["desktopAuthorizedContext"])
        DesktopAIConsent.requireFresh(snapshot,state(),context)
    }

    @Test fun editedOrForgottenAuthorizedContextRequiresNewPreview() {
        val context=DesktopAIContext(feedback=listOf(feedback))
        val snapshot=DesktopAIConsent.prepare(base,state(),context,"task-1",true,true)
        assertTrue(runCatching {DesktopAIConsent.requireFresh(snapshot,state("新背景"),context)}.isFailure)
        assertTrue(runCatching {DesktopAIConsent.requireFresh(snapshot,state(),DesktopAIContext())}.isFailure)
    }

    @Test fun addingContextCannotBypassTheExistingRequestSizeLimit() {
        val large=buildJsonObject {put("evidence",buildJsonObject {put("note","x".repeat(31_950))})}.toString()
        assertTrue(runCatching {DesktopAIConsent.prepare(large,state(),DesktopAIContext(),"task-1",true,false)}.exceptionOrNull()?.message?.contains("32")==true)
    }

    @Test fun runtimeRejectsChangedAuthorizedBackgroundBeforeAnyTransportCall() = runBlocking {
        val calls=AtomicInteger()
        DesktopRuntime(Files.createTempDirectory("step-consent-runtime-"),DesktopConfig(),{_,_->},TestProtector(),NoNetwork,FixtureClock(),MobileAIClient {_,_,_->calls.incrementAndGet();error("Transport must not run for stale consent")}).use {runtime->
            runtime.initialize()
            val taskId="11111111-1111-4111-8111-111111111111"
            runtime.edit {current->
                val profile=PreferencesActions.saveProfile(current,ProfileDraft(background="虚构背景"),ProfileBaseline.from(null))
                StateMachine.edit(profile,"task",taskId,ContentActions.save(null,"task","虚构行动","",1,taskId,Instant.now()))
            }.getOrThrow()
            runtime.saveAI(MobileAIConfig("https://fixture.invalid/chat","fixture"),"fictional-key").getOrThrow()
            val baseInput=MobileAIContext.prepare(runtime.engine.state.value,taskId,MobileAIOptions(),emptyList())
            val consent=DesktopAIConsent.prepare(baseInput.preview,runtime.engine.state.value,DesktopAIContext(),taskId,true,false)
            assertEquals(0,calls.get())
            runtime.edit {current->PreferencesActions.saveProfile(current,ProfileDraft(background="已经修改"),ProfileBaseline.from(current.items["profile:profile"]))}.getOrThrow()
            val result=runtime.generateAI(baseInput.copy(preview=consent.preview)) {DesktopAIConsent.requireFresh(consent,runtime.engine.state.value,runtime.aiContext.value)}
            assertTrue(result.isFailure)
            assertEquals(0,calls.get())
            assertTrue(runtime.aiHistory.value.isEmpty())
        }
    }


    @Test fun transportReceivesExactlyTheReviewedPreviewIncludingAuthorizedEvidence() = runBlocking {
        val received=AtomicReference<String>()
        val client=MobileAIClient {_,_,body->
            received.set(body)
            val sent=wire.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content
            val input=wire.parseToJsonElement(sent).jsonObject
            val proposal=buildJsonObject {
                put("requestId",input.getValue("requestId"));put("taskId",input.getValue("taskId"))
                put("nextAction","打开虚构笔记");put("stopCriterion","看到标题就停");put("minutes",3);put("reason","参考你授权的背景与反馈")
            }
            MobileAIResponse(200,buildJsonObject {put("choices",buildJsonArray {add(buildJsonObject {
                put("finish_reason","stop");put("message",buildJsonObject {put("content",proposal.toString())})
            })})}.toString())
        }
        DesktopRuntime(Files.createTempDirectory("step-consent-wire-"),DesktopConfig(),{_,_->},TestProtector(),NoNetwork,FixtureClock(),client).use {runtime->
            runtime.initialize()
            val taskId="11111111-1111-4111-8111-111111111111"
            runtime.edit {current->
                val profile=PreferencesActions.saveProfile(current,ProfileDraft(background="虚构背景"),ProfileBaseline.from(null))
                StateMachine.edit(profile,"task",taskId,ContentActions.save(null,"task","虚构行动","",1,taskId,Instant.now()))
            }.getOrThrow()
            runtime.saveAI(MobileAIConfig("https://fixture.invalid/chat","fixture"),"fictional-key").getOrThrow()
            runtime.recordAIFeedback(feedback.copy(subjectId=taskId)).getOrThrow()
            val baseInput=MobileAIContext.prepare(runtime.engine.state.value,taskId,MobileAIOptions(),emptyList())
            val consent=DesktopAIConsent.prepare(baseInput.preview,runtime.engine.state.value,runtime.aiContext.value,taskId,true,true)
            assertNull(received.get())
            runtime.generateAI(baseInput.copy(preview=consent.preview)) {DesktopAIConsent.requireFresh(consent,runtime.engine.state.value,runtime.aiContext.value)}.getOrThrow()
            val sent=wire.parseToJsonElement(received.get()).jsonObject.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content
            assertEquals(consent.preview,sent)
            assertTrue(sent.contains("虚构背景"))
            assertTrue(sent.contains("虚构反馈"))
            assertEquals(received.get(),runtime.aiHistory.value.single().input)
        }
    }

}
