package com.step.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.pare.app.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import javax.imageio.ImageIO

/**
 * The same fictional content as Scripts/ReadmeDemo.swift, rendered without an OS window.
 * Host names in output filenames distinguish Mac headless renders from Windows evidence.
 * This adds visual coverage; DesktopWorkflowUiTest remains the functional workflow gate.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopVisualFixtureTest {
    @Test fun darkWideDemo() = renderDemo(dark=true,width=1280,height=900)
    @Test fun lightWideDemo() = renderDemo(dark=false,width=1280,height=900)
    @Test fun darkCompactDemo() = renderDemo(dark=true,width=960,height=760)
    @Test fun lightCompactDemo() = renderDemo(dark=false,width=960,height=760)
    @Test fun darkWideHighDensityDemo() = renderDemo(dark=true,width=1280,height=900,renderDensity=2f)
    @Test fun lightWideHighDensityDemo() = renderDemo(dark=false,width=1280,height=900,renderDensity=2f)

    private fun renderDemo(dark:Boolean,width:Int,height:Int,renderDensity:Float=1f) {
        val sceneDensity=Density(renderDensity,fontScale=1f)
        // Canvas dimensions are physical pixels; layout and typography still use logical dp/sp.
        val test=DesktopComposeUiTest(
            width=with(sceneDensity){width.dp.roundToPx()},
            height=with(sceneDensity){height.dp.roundToPx()},
            density=sceneDensity
        )
        test.runTest {
            with(test) {
                val runtime=demoRuntime()
                try {
                    DesktopAppearance.dark.value=dark
                    setContent {DesktopUi(runtime,{}, {}, {})}
                    waitForIdle()

                    // Three unfinished selections, one completion, a daily focus and two real time blocks.
                    val planId=ContentActions.planId(runtime.engine.state.value,Instant.now())
                    val day=TimelineDay.fromPlanId(planId)
                    val today=TodayOverviewProjection.project(runtime.engine.state.value,day)
                    assertEquals(3,(today.scheduled+today.unscheduled).distinctBy {it.task.id}.size)
                    assertEquals(1,today.completed.size)
                    assertEquals(2,TimelineProjection.project(runtime.engine.state.value,day).blocks.size)
                    assertTrue(planWin(runtime.engine.state.value,planId,REPORT))
                    onNodeWithTag("nav_今天").assertIsDisplayed()
                    // A container change must not turn the uncolored page title black on the dark surface.
                    val titlePixels=onNodeWithTag("workspace_title").captureToImage().toPixelMap()
                    val titleLuminance=(0 until titlePixels.height).flatMap {y->(0 until titlePixels.width).map {x->titlePixels[x,y].luminance()}}
                    assertTrue("Page title must remain readable in the selected appearance",
                        if(dark)titleLuminance.any {it>.45f}else titleLuminance.any {it<.20f})
                    onAllNodesWithText("完成报告提纲").onFirst().assertIsDisplayed()
                    capture(dark,width,height,"today","desktop_root",fullWindow=true)

                    onNodeWithTag("view_timeline").performClick()
                    onNodeWithTag("timeline_grid").assertExists()
                    capture(dark,width,height,"timeline","desktop_root",fullWindow=true)
                    onNodeWithTag("nav_资料库").performClick()
                    onNodeWithTag("library_scope_COMPLETED").performClick()
                    onNodeWithTag("library_$DONE").assertIsDisplayed()
                    capture(dark,width,height,"library","desktop_root",fullWindow=true)
                    onNodeWithTag("nav_回顾").performClick()
                    onNodeWithTag("review_export").assertIsDisplayed()
                    capture(dark,width,height,"review","desktop_root",fullWindow=true)

                    onNodeWithTag("nav_四象限").performClick()
                    listOf(REPORT,TRIP,REPLY,BOOKMARKS).forEach {onNodeWithTag("task_$it").assertIsDisplayed()}
                    capture(dark,width,height,"quadrants","desktop_root",fullWindow=true)

                    onNodeWithTag("task_$REPORT").performClick()
                    onNode(hasSetTextAction() and hasText("下一步具体做什么？"))
                        .assertTextContains("打开文档，先写三个小标题")
                    onNodeWithTag("step_history_toggle").performScrollTo().performClick()
                    assertDialogContext("完成报告提纲")
                    capture(dark,width,height,"task-detail","desktop_dialog")
                    onNodeWithText("整理已有的报告素材").performScrollTo().assertIsDisplayed()
                    // Bring the complete history record, including its date and undo affordance, into view.
                    onNodeWithText("撤销最近这一步").performScrollTo().assertIsDisplayed()
                    onNodeWithText("整理已有的报告素材").assertIsDisplayed()
                    assertDialogContext("完成报告提纲")
                    capture(dark,width,height,"task-history","desktop_dialog")
                    onNodeWithText("关闭").performClick()
                    onNodeWithTag("desktop_dialog").assertDoesNotExist()

                    onNodeWithTag("nav_想法").performClick()
                    onNodeWithTag("idea_$RESEARCH").assertIsDisplayed().performClick()
                    assertDialogContext("探索适合自己的研究流程")
                    onNode(hasSetTextAction() and hasText("下一次想弄清的问题"))
                        .assertTextContains("什么样的记录能帮助下一次开始？")
                    capture(dark,width,height,"idea-context","desktop_dialog")

                    onNodeWithText("探索记录").performClick()
                    onNodeWithText(FINDING).performScrollTo().assertIsDisplayed()
                    assertDialogContext("探索适合自己的研究流程")
                    capture(dark,width,height,"idea-exploration","desktop_dialog")
                    onNodeWithText("关联行动").performScrollTo().performClick()
                    onNodeWithText("完成报告提纲").assertIsDisplayed()
                    assertEquals(listOf(REPORT),runtime.engine.state.value.items.getValue("idea:$RESEARCH")
                        .content().array("linkedTaskIDs").map {it.jsonPrimitive.content})
                } finally {
                    runtime.close()
                    DesktopAppearance.dark.value=true
                }
            }
        }
    }

    private fun DesktopComposeUiTest.assertDialogContext(title:String) {
        // Context must remain in the modal when its scrollable body changes tabs or scroll position.
        val dialog=onNodeWithTag("desktop_dialog").assertIsDisplayed()
        dialog.assert(hasAnyDescendant(hasText(title,substring=true)))
        val bounds=dialog.fetchSemanticsNode().boundsInRoot
        val matching=onAllNodes(
            hasText(title,substring=true) and hasAnyAncestor(hasTestTag("desktop_dialog")),
            useUnmergedTree=true
        ).fetchSemanticsNodes()
        val headerBandPixels=with(density){130.dp.toPx()}
        assertTrue("The task or idea name must remain in the fixed modal header",matching.any {
            it.boundsInRoot.top >= bounds.top && it.boundsInRoot.top < bounds.top+headerBandPixels && it.boundsInRoot.bottom<=bounds.bottom
        })
    }

    private fun DesktopComposeUiTest.capture(dark:Boolean,width:Int,height:Int,name:String,tag:String,fullWindow:Boolean=false) {
        waitForIdle()
        val bitmap=onNodeWithTag(tag).captureToImage()
        val pixelWidth=with(density){width.dp.roundToPx()}
        val pixelHeight=with(density){height.dp.roundToPx()}
        if(fullWindow) {
            assertEquals("Desktop capture pixel width",pixelWidth,bitmap.width)
            assertEquals("Desktop capture pixel height",pixelHeight,bitmap.height)
            onNodeWithTag(tag).assertWidthIsEqualTo(width.dp).assertHeightIsEqualTo(height.dp)
        } else {
            val minWidth=with(density){600.dp.roundToPx()}
            val minHeight=with(density){350.dp.roundToPx()}
            assertTrue("Modal must fit inside the rendering surface",bitmap.width in minWidth..pixelWidth && bitmap.height in minHeight..pixelHeight)
        }
        val output=Path.of("build","test-screenshots")
        Files.createDirectories(output)
        val host=System.getProperty("os.name").lowercase().replace(Regex("[^a-z0-9]+"),"-")
        val theme=if(dark)"dark"else"light"
        val densitySuffix=if(density.density==1f)""else"-${density.density.toInt()}x"
        val file=output.resolve("visual-$host-$theme-$width$densitySuffix-$name.png")
        assertTrue("PNG writer must produce the visual evidence",ImageIO.write(bitmap.toAwtImage(),"png",file.toFile()))
        assertTrue("Visual evidence must contain a non-empty rendered image",Files.size(file)>5_000)
    }

    private suspend fun demoRuntime():DesktopRuntime {
        val runtime=DesktopRuntime(
            Files.createTempDirectory("step-readme-visual-fixture-"),DesktopConfig(),{_,_->},
            TestProtector(),VisualNoNetwork,FixtureClock(),
            MobileAIClient {_,_,_->error("Visual fixture must never request AI")}
        )
        try {
            runtime.initialize()
            val now=Instant.now()
            runtime.edit {original->seedDemo(original,now)}.getOrThrow()
            return runtime
        } catch(error:Throwable) {
            runtime.close()
            throw error
        }
    }

    private fun seedDemo(original:SyncState,now:Instant):SyncState {
        // A fixed planning zone keeps calendar labels comparable on Mac and Windows CI hosts.
        val profile=buildJsonObject {put("content",buildJsonObject {
            put("background","");put("planningTimeZone","Asia/Shanghai")
            put("preferences",buildJsonObject {put("planningMinutes",60);put("startingMinutes",5);put("notes","")})
            put("confirmedPreferences",JsonArray(emptyList()))
        })}
        PayloadValidation.validate("profile","profile",profile)
        var state=StateMachine.edit(original,"profile","profile",profile)
        val tasks=listOf(
            DemoTask(REPORT,"完成报告提纲",0,25),
            DemoTask(CONFIRM,"确认明天的预约",0,5),
            DemoTask(TRIP,"整理周末旅行计划",1,30),
            DemoTask(BOOK,"读完一章书并记下想法",1,null),
            DemoTask(REPLY,"回复活动时间确认",2,5),
            DemoTask(BOOKMARKS,"整理收藏夹",3,15),
            DemoTask(DONE,"预约年度体检",1,10)
        )
        tasks.forEachIndexed {index,task->
            state=StateMachine.edit(state,"task",task.id,ContentActions.save(
                null,"task",task.title,"",task.quadrant,task.id,now.minusSeconds(3_600+(10-index).toLong()),
                estimatedMinutes=task.minutes
            ))
        }

        // A short completed step supplements the Mac demo so the desktop's history is reviewable.
        state=NextStepActions.save(state,REPORT,"整理已有的报告素材","找到本次需要参考的三份材料",
            SmallStepBaseline.from(state.items.getValue("task:$REPORT")),now.minusSeconds(600))
        state=NextStepActions.complete(state,REPORT,COMPLETED_STEP,
            SmallStepBaseline.from(state.items.getValue("task:$REPORT")),now.minusSeconds(300))
        state=NextStepActions.save(state,REPORT,"打开文档，先写三个小标题","列出结构和要点，暂时不用润色",
            SmallStepBaseline.from(state.items.getValue("task:$REPORT")),now)

        val planId=ContentActions.planId(state,now)
        listOf(REPORT,TRIP,CONFIRM,DONE).forEach {id->state=DayTaskActions.setIncluded(state,id,true,planId,now)}
        state=DayTaskActions.setWin(state,REPORT,true,planId,now)
        listOf(Triple(REPORT,"personal",30),Triple(TRIP,"personal",30),Triple(CONFIRM,"check",15)).forEachIndexed {index,(id,kind,minutes)->
            state=DayPlanActions.allocate(state,id,TodayAllocationDraft(
                planId,kind,minutes,allocationId(index),DayAllocationBaseline.from(state,id,now)
            ),now)
        }
        val start=now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().atTime(9,30).atZone(ZoneId.of("Asia/Shanghai")).toInstant()
        state=TimelineActions.setSchedule(state,REPORT,planId,start,30,TimelineScheduleBaseline.from(state,planId,REPORT))
        state=TimelineActions.setSchedule(state,CONFIRM,planId,start.plusSeconds(30*60),15,TimelineScheduleBaseline.from(state,planId,CONFIRM))
        state=TaskMetadataActions.save(state,DONE,TaskMetadataBaseline.from(state.items.getValue("task:$DONE")),null,8,now)
        state=CompletionActions.set(state,DONE,true,now)

        state=GoalsActions.save(state,GoalDraft(id=GOAL,title="本月完成一份结构清晰的报告",
            motivation="先理清结构，再逐步补全内容。",taskIds=listOf(REPORT)),GoalBaseline.from(null),now)
        listOf(INBOX_DESK to "找个时间整理书桌",INBOX_MAP to "给旅行相册加一页手绘地图").forEach {(id,text)->
            state=StateMachine.edit(state,"inbox",id,ContentActions.save(null,"inbox",text,"",1,id,now))
        }
        state=StateMachine.edit(state,"idea",AFTERNOON,ContentActions.save(null,"idea","每周留一个不排计划的下午","散步、阅读，或者什么都不做。",1,AFTERNOON,now.minusSeconds(60)))
        state=StateMachine.edit(state,"idea",RESEARCH,ContentActions.save(null,"idea","探索适合自己的研究流程","从真实问题出发，逐步整理成可以复用的方法。",1,RESEARCH,now))
        state=IdeaActions.saveContext(state,RESEARCH,"exploring","什么样的记录能帮助下一次开始？",
            IdeaContextBaseline.from(state.items.getValue("idea:$RESEARCH")),now)
        state=IdeaActions.appendEntry(state,RESEARCH,FINDING_ID,FINDING,"finding",now)
        state=IdeaManagementActions.link(state,RESEARCH,REPORT,true,
            IdeaManagementBaseline.from(state.items.getValue("idea:$RESEARCH")),now)
        state=IdeaManagementActions.review(state,RESEARCH,now.plusSeconds(7*86_400),
            IdeaManagementBaseline.from(state.items.getValue("idea:$RESEARCH")),now)
        return state
    }

    private data class DemoTask(val id:String,val title:String,val quadrant:Int,val minutes:Int?)
    private fun allocationId(index:Int)="00000000-0000-4000-8000-${(701+index).toString().padStart(12,'0')}"

    private companion object {
        const val REPORT="00000000-0000-4000-8000-000000000501"
        const val CONFIRM="00000000-0000-4000-8000-000000000502"
        const val TRIP="00000000-0000-4000-8000-000000000503"
        const val BOOK="00000000-0000-4000-8000-000000000504"
        const val REPLY="00000000-0000-4000-8000-000000000505"
        const val BOOKMARKS="00000000-0000-4000-8000-000000000506"
        const val DONE="00000000-0000-4000-8000-000000000507"
        const val RESEARCH="00000000-0000-4000-8000-000000000601"
        const val AFTERNOON="00000000-0000-4000-8000-000000000602"
        const val GOAL="00000000-0000-4000-8000-000000000603"
        const val INBOX_DESK="00000000-0000-4000-8000-000000000604"
        const val INBOX_MAP="00000000-0000-4000-8000-000000000605"
        const val COMPLETED_STEP="00000000-0000-4000-8000-000000000606"
        const val FINDING_ID="00000000-0000-4000-8000-000000000607"
        const val FINDING="先把一次探索缩小到一个问题，不要求一次完成整个方向。"
    }
}

private object VisualNoNetwork:SyncRemote {
    override suspend fun login(email:String,password:String,signup:Boolean):Session?=error("Visual fixture forbids network")
    override suspend fun refresh(session:Session):Session=error("Visual fixture forbids network")
    override suspend fun pull(session:Session,cursor:Long):Page=error("Visual fixture forbids network")
    override suspend fun push(session:Session,request:Push):Reply=error("Visual fixture forbids network")
    override suspend fun logout(session:Session)=error("Visual fixture forbids network")
}
