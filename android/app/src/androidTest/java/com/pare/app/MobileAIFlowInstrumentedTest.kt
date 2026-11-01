package com.pare.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*

/** Entire UI flow, real task transactions, and a provider that can never reach the network. */
class MobileAIFlowInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val taskId="00000000-0000-4000-8000-000000000071"
    private val calls=AtomicInteger()
    private val sentBody=AtomicReference<String>()
    private lateinit var scenario:ActivityScenario<ComponentActivity>
    private lateinit var repository:PareRepository
    private lateinit var engine:SyncEngine
    private lateinit var model:MobileAIModel
    private val privateStore=MemoryAIStore()

    private class MemoryAIStore:MobileAIStore {
        @Volatile private var value=MobileAIPrivateData()
        override fun load()=value
        override fun save(data:MobileAIPrivateData) {value=data}
        override fun clear() {value=MobileAIPrivateData()}
    }
    @Before fun launchIsolatedFlow() {
        check(context.packageName=="com.pare.app.qa")
        repository=PareRepository(context)
        val now=Instant.parse("2026-10-30T00:00:00Z")
        repository.update {
            val payload=ContentActions.save(null,"task","虚构示例：画出花园的一条边","只探索纸上布局，不购买材料",1,taskId,now,
                estimatedMinutes=45,nextAction="先找一张纸")
            SyncState(device="fictional-ai-flow",items=mapOf("task:$taskId" to Entity("task",taskId,payload)))
        }
        engine=SyncEngine(repository,object:SessionStore {
            override fun load():Session?=null
            override fun save(session:Session) {error("This isolated flow cannot authenticate")}
            override fun clear() {}
        },object:SyncRemote {
            override suspend fun login(email:String,password:String,signup:Boolean):Session?=error("Network is forbidden")
            override suspend fun refresh(session:Session):Session=error("Network is forbidden")
            override suspend fun pull(session:Session,cursor:Long):Page=error("Network is forbidden")
            override suspend fun push(session:Session,request:Push):Reply=error("Network is forbidden")
            override suspend fun logout(session:Session) {error("Network is forbidden")}
        },object:SyncSchedule {
            override fun schedule(hasPending:Boolean) {error("Sync is disabled in this isolated flow")}
            override fun cancel() {}
        })
        runBlocking(Dispatchers.IO) {engine.initialize()}
        val client=MobileAIClient(MobileAITransport {config,key,body ->
            assertEquals("https://fictional-model.invalid/v1/chat/completions",config.endpoint)
            assertEquals("fictional-unusable-api-key",key)
            val round=calls.incrementAndGet();sentBody.set(body)
            val user=wire.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonPrimitive.content
            val input=wire.parseToJsonElement(user).jsonObject
            val reply=MobileAIProposal(input.getValue("requestId").jsonPrimitive.content,input.getValue("taskId").jsonPrimitive.content,
                if(round==1) "在纸上画一条短边" else "在短边旁补一个角点",
                if(round==1) "画出第一条线就可以停" else "标出一个角点就可以停",
                3,"根据当前任务的纸上探索范围，先完成一条边，不需要购买材料。")
            MobileAIResponse(200,buildJsonObject {put("choices",buildJsonArray {add(buildJsonObject {
                put("finish_reason","stop");put("message",buildJsonObject {put("content",wire.encodeToString(reply))})
            })})}.toString())
        })
        scenario=ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity {activity ->
            model=ViewModelProvider(activity,object:ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T = MobileAIModel(activity.application,privateStore,client) as T
            })[MobileAIModel::class.java]
            activity.setContent {
                val state by engine.state.collectAsState()
                PareTheme {MobileAIDialog(state,taskId,onEdit={change,complete ->
                    activity.lifecycleScope.launch {
                        val result=withContext(Dispatchers.IO) {runCatching {engine.edit(change);Unit}}
                        complete(result)
                    }
                },onDismiss={},model=model)}
            }
        }
        compose.waitUntil(10_000) {model.ready}
    }
    @After fun closeIsolatedFlow() {
        if(::scenario.isInitialized) scenario.close()
        if(::repository.isInitialized) repository.close()
    }
    private fun visible(tag:String) {compose.waitUntil(10_000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
    private fun screenshotOrPrivacyNote(name:String) {
        compose.waitForIdle()
        val output=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: File(context.getExternalFilesDir(null),"ui-acceptance")
        output.mkdirs()
        compose.onNodeWithTag("ai_dialog").assertExists()
        val bitmap=runCatching {compose.onNodeWithTag("ai_dialog").captureToImage().asAndroidBitmap()}.getOrElse {e ->
            // SecureOn remains enabled. A secure-window PixelCopy failure is evidence, never grounds to disable privacy.
            File(output,"$name-screenshot-unavailable.txt").writeText("AI dialog keeps SecureFlagPolicy.SecureOn. captureToImage was unavailable (${e.javaClass.simpleName}); privacy flags were not changed. Only fictional fixtures were used.")
            return
        }
        File(output,"$name.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
    }
    private fun configureService(captureSettings:Boolean=false) {
        compose.onNodeWithTag("ai_settings_toggle").performClick()
        compose.onNodeWithTag("ai_endpoint").performScrollTo().performTextReplacement("https://fictional-model.invalid/v1/chat/completions")
        compose.onNodeWithTag("ai_model").performScrollTo().performTextReplacement("fictional-model")
        Espresso.closeSoftKeyboard()
        // Capture settings before entering even the dummy key; no key appears in this artifact.
        if(captureSettings) screenshotOrPrivacyNote("16-ai-local-settings")
        compose.onNodeWithTag("ai_key").performScrollTo().performTextReplacement("fictional-unusable-api-key")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("ai_settings_save").performScrollTo().performClick()
        compose.waitUntil(10_000) {privateStore.load().config.model=="fictional-model" && !model.saving}
        compose.waitUntil(10_000) {compose.onAllNodesWithTag("ai_key").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithTag("ai_context_options").assertDoesNotExist()
        compose.onNodeWithTag("ai_scope_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("ai_context_options").assertExists()
    }
    @Test fun previewGenerateAdoptUndoAndDeletePrivateTrace() {
        val before=repository.read()
        configureService(captureSettings=true)
        assertEquals(0,calls.get())
        compose.onNodeWithTag("ai_preview").performScrollTo().performClick()
        compose.onNodeWithTag("ai_context_preview").assertDoesNotExist()
        compose.onNodeWithTag("ai_full_context_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("ai_context_preview").performScrollTo().assertTextContains("只探索纸上布局，不购买材料",substring=true)
        assertEquals(0,calls.get())
        val reviewed=model.draft(taskId).input!!.preview
        val authorizedOptions=model.draft(taskId).options
        // Disclosure controls must only change visibility, never regenerate the reviewed input.
        compose.onNodeWithTag("ai_scope_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("ai_context_options").assertDoesNotExist()
        compose.onNodeWithTag("ai_scope_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("ai_context_options").assertExists()
        assertEquals(authorizedOptions,model.draft(taskId).options)
        assertEquals(reviewed,model.draft(taskId).input!!.preview)
        assertEquals(0,calls.get())
        compose.onNodeWithTag("ai_generate").performClick()
        visible("ai_proposal")
        compose.waitUntil(10_000) {!model.draft(taskId).busy && privateStore.load().history.size==1}
        assertEquals(1,calls.get())
        val sent=wire.parseToJsonElement(sentBody.get()).jsonObject.getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonPrimitive.content
        assertEquals(reviewed,sent)
        compose.onNodeWithTag("ai_context_options").assertDoesNotExist()
        compose.onNodeWithTag("ai_context_preview").assertDoesNotExist()
        compose.onNodeWithTag("ai_task_context").assertIsDisplayed().assertTextContains("当前任务 · 虚构示例：画出花园的一条边",substring=true)
        compose.onNodeWithTag("ai_proposal").assertIsDisplayed().assertTextEquals("在纸上画一条短边")
        compose.onNodeWithTag("ai_stop").assertIsDisplayed().assertTextContains("画出第一条线就可以停",substring=true)
        compose.onNodeWithTag("ai_reason").assertDoesNotExist()
        compose.onNodeWithTag("ai_reason_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("ai_reason").performScrollTo().assertTextContains("纸上探索范围",substring=true)
        compose.onNodeWithTag("ai_reason_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("ai_reason").assertDoesNotExist()
        for(tag in listOf("ai_scope_toggle","ai_settings_toggle","ai_history_toggle")) {
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            compose.onNodeWithTag(tag).performScrollTo().performClick()
        }
        assertEquals(authorizedOptions,model.draft(taskId).options)
        assertEquals(reviewed,model.draft(taskId).input!!.preview)
        assertEquals(1,calls.get())
        assertEquals("先找一张纸",repository.read().items.getValue("task:$taskId").content()!!.getValue("nextAction").jsonPrimitive.content)
        assertEquals(before,repository.read())
        compose.onNodeWithTag("ai_task_context").performScrollTo().assertIsDisplayed().assertTextContains("虚构示例：画出花园的一条边",substring=true)
        compose.onNodeWithTag("ai_proposal").assertIsDisplayed()
        screenshotOrPrivacyNote("17-ai-reviewed-suggestion")
        compose.onNodeWithTag("ai_adopt").performClick()
        visible("ai_undo")
        compose.onNodeWithTag("ai_status").performScrollTo().assertTextEquals("已设为最小下一步，可撤销本次采纳")
        compose.onNodeWithTag("ai_task_context").performScrollTo().assertIsDisplayed().assertTextContains("虚构示例：画出花园的一条边",substring=true)
        val adopted=repository.read().items.getValue("task:$taskId").content()!!
        assertEquals("在纸上画一条短边",adopted.getValue("nextAction").jsonPrimitive.content)
        assertEquals("画出第一条线就可以停",adopted.getValue("definitionOfDone").jsonPrimitive.content)
        assertEquals(45,adopted.getValue("estimatedMinutes").jsonPrimitive.int)
        assertFalse(wire.encodeToString(repository.read()).contains("fictional-unusable-api-key"))
        compose.onNodeWithTag("ai_undo").performClick()
        compose.waitUntil(10_000) {model.draft(taskId).undo==null && !model.draft(taskId).busy}
        val restored=repository.read().items.getValue("task:$taskId").content()!!
        assertEquals("先找一张纸",restored.getValue("nextAction").jsonPrimitive.content)
        assertFalse(restored.containsKey("definitionOfDone"))
        compose.onNodeWithTag("ai_history_toggle").performScrollTo().performClick()
        val record=privateStore.load().history.single()
        compose.onNodeWithTag("ai_trace_${record.id}").performScrollTo().performClick()
        compose.onNodeWithTag("ai_trace_output_${record.id}").performScrollTo().assertTextContains("在纸上画一条短边",substring=true)
        assertFalse(record.input.contains("fictional-unusable-api-key"))
        assertFalse(record.output.contains("fictional-unusable-api-key"))
        compose.onNodeWithTag("ai_trace_delete_${record.id}").performScrollTo().performClick()
        compose.waitUntil(10_000) {privateStore.load().history.isEmpty() && !model.saving}
        compose.onNodeWithTag("ai_trace_${record.id}").assertDoesNotExist()
        assertEquals(1,calls.get())
    }
    @Test fun continuingAfterAdoptionPreservesFirstRoundAndUndoOnlyRevertsTheLatestAdoption() {
        configureService()
        compose.onNodeWithTag("ai_preview").performScrollTo().performClick()
        compose.onNodeWithTag("ai_context_preview").assertDoesNotExist()
        compose.onNodeWithTag("ai_full_context_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("ai_context_preview").performScrollTo().assertTextContains("只探索纸上布局，不购买材料",substring=true)
        compose.onNodeWithTag("ai_generate").performClick()
        visible("ai_proposal")
        compose.waitUntil(10_000) {!model.draft(taskId).busy}
        compose.onNodeWithTag("ai_context_options").assertDoesNotExist()
        compose.onNodeWithTag("ai_context_preview").assertDoesNotExist()
        compose.onNodeWithTag("ai_proposal").assertIsDisplayed()
        compose.onNodeWithTag("ai_stop").assertIsDisplayed()
        compose.onNodeWithTag("ai_adopt").performClick()
        visible("ai_undo")
        val adoptedFirst=repository.read()
        val firstRequestId=model.draft(taskId).input!!.id
        compose.onNodeWithTag("ai_next_round").performScrollTo().performClick()
        compose.onNodeWithTag("ai_undo").assertDoesNotExist()
        compose.onNodeWithTag("ai_proposal").assertDoesNotExist()
        compose.onNodeWithTag("ai_generate").assertIsEnabled()
        compose.onNodeWithTag("ai_full_context_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("ai_context_preview").performScrollTo().assertTextContains("在纸上画一条短边",substring=true)
        assertEquals(adoptedFirst,repository.read())
        assertEquals(1,calls.get())
        assertNotEquals(firstRequestId,model.draft(taskId).input!!.id)
        compose.onNodeWithTag("ai_generate").performClick()
        visible("ai_proposal")
        compose.waitUntil(10_000) {!model.draft(taskId).busy && calls.get()==2}
        compose.onNodeWithTag("ai_proposal").assertIsDisplayed().assertTextEquals("在短边旁补一个角点")
        compose.onNodeWithTag("ai_stop").assertIsDisplayed()
        compose.onNodeWithTag("ai_reason").assertDoesNotExist()
        compose.onNodeWithTag("ai_reason_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("ai_reason").performScrollTo().assertTextContains("纸上探索范围",substring=true)
        compose.onNodeWithTag("ai_reason_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("ai_reason").assertDoesNotExist()
        compose.onNodeWithTag("ai_adopt").performClick()
        visible("ai_undo")
        assertEquals("在短边旁补一个角点",repository.read().items.getValue("task:$taskId").content()!!.getValue("nextAction").jsonPrimitive.content)
        compose.onNodeWithTag("ai_undo").performClick()
        compose.waitUntil(10_000) {model.draft(taskId).undo==null && !model.draft(taskId).busy}
        val restored=repository.read().items.getValue("task:$taskId").content()!!
        assertEquals("在纸上画一条短边",restored.getValue("nextAction").jsonPrimitive.content)
        assertEquals("画出第一条线就可以停",restored.getValue("definitionOfDone").jsonPrimitive.content)
        assertEquals(2,calls.get())
        assertEquals(2,privateStore.load().history.size)
    }
}
