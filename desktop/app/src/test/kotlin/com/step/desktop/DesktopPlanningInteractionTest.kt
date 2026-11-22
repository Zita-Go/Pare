package com.step.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import javax.imageio.ImageIO
import java.nio.file.Path
import androidx.compose.ui.test.*
import com.pare.app.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

/** Real pointer workflows; isolated fictional data and no network/model transport. */
@OptIn(ExperimentalTestApi::class)
class DesktopPlanningInteractionTest {
    @Test fun draggingAcrossQuadrantsInsertsAtTargetAndUndoRestores() = runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {r->
            setContent {DesktopUi(r,{}, {}, {})}
            onNodeWithTag("nav_四象限").performClick()
            drag("quadrant_drag_$A","task_$B",before=true)
            waitUntil(timeoutMillis=5_000){r.engine.state.value.items.getValue("task:$A").content().number("quadrant")==1}
            assertEquals(listOf(A,B,C),OrganizeActions.tasks(r.engine.state.value,1).map {it.id})
            onNodeWithTag("organize_undo").performClick()
            waitUntil(timeoutMillis=5_000){r.engine.state.value.items.getValue("task:$A").content().number("quadrant")==0}
            assertEquals(listOf(B,C),OrganizeActions.tasks(r.engine.state.value,1).map {it.id})
        }
    }
    @Test fun todayDragChangesOnlyOrderAndCanBeUndone() = runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {r->
            val plan=ContentActions.planId(r.engine.state.value,Instant.now())
            setContent {DesktopUi(r,{}, {}, {})}
            drag("today_drag_$C","task_$A",before=true)
            waitUntil(timeoutMillis=5_000){ordered(r,plan)==listOf(C,A,B)}
            assertTrue(r.engine.state.value.items.values.filter {it.kind=="task"}.none {it.completed()})
            onNodeWithTag("today_order_undo").performClick()
            waitUntil(timeoutMillis=5_000){ordered(r,plan)==listOf(A,B,C)}
        }
    }
    @Test fun timelineDropMoveResizeAndCrossDayAreRealTransactions() = runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {r->
            val plan=ContentActions.planId(r.engine.state.value,Instant.now());val day=TimelineDay.fromPlanId(plan)
            setContent {DesktopUi(r,{}, {}, {})}
            onNodeWithTag("view_timeline").performClick()
            drag("timeline_unscheduled_$A","timeline_slot_540")
            waitUntil(timeoutMillis=5_000){planEntry(r.engine.state.value,plan,A).str("scheduled_at").isNotEmpty()}
            assertEquals(day.start.plusSeconds(540*60L).toString(),planEntry(r.engine.state.value,plan,A).str("scheduled_at"))
            drag("timeline_drag_$A","timeline_slot_600")
            waitUntil(timeoutMillis=5_000){planEntry(r.engine.state.value,plan,A).str("scheduled_at")==day.start.plusSeconds(600*60L).toString()}
            onNodeWithTag("timeline_slot_690").performScrollTo()
            val ppm=onNodeWithTag("timeline_grid").fetchSemanticsNode().size.height.toFloat()/day.minutes
            val resize=onNodeWithTag("timeline_resize_$A").fetchSemanticsNode().boundsInRoot.center
            mouseDrag(resize,resize+Offset(0f,60f*ppm))
            waitUntil(timeoutMillis=5_000){planEntry(r.engine.state.value,plan,A).number("scheduled_minutes")==90}
            drag("timeline_drag_$A","timeline_day_next")
            val next=TimelineDay(day.date.plusDays(1),day.zone)
            waitUntil(timeoutMillis=5_000){planEntry(r.engine.state.value,next.planId,A).number("scheduled_minutes")==90}
            assertEquals("",planEntry(r.engine.state.value,plan,A).str("scheduled_at"))
            onNodeWithTag("timeline_undo").performClick()
            waitUntil(timeoutMillis=5_000){planEntry(r.engine.state.value,plan,A).number("scheduled_minutes")==90}
        }
    }
    @Test fun emptyTimelineSlotCreatesAndSchedulesWithoutStartingFocus() = runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {r->
            setContent {DesktopUi(r,{}, {}, {})}
            onNodeWithTag("view_timeline").performClick()
            onNodeWithTag("timeline_slot_660").performClick()
            onNodeWithTag("timeline_new_title").performTextReplacement("虚构：给报告写摘要")
            onNodeWithTag("timeline_create").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000){r.engine.state.value.items.values.any {it.kind=="task"&&it.title()=="虚构：给报告写摘要"}}
            val task=r.engine.state.value.items.values.single {it.kind=="task"&&it.title()=="虚构：给报告写摘要"}
            val day=TimelineDay.today(r.engine.state.value,Instant.now())
            assertEquals(day.start.plusSeconds(660*60L).toString(),planEntry(r.engine.state.value,day.planId,task.id).str("scheduled_at"))
            assertNull(r.execution.state.value.focus)
        }
    }
    @Test fun rowQuickFocusAndNextStepDoNotCompleteTheParent() = runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {r->
            setContent {DesktopUi(r,{}, {}, {})}
            onNodeWithTag("quick_focus_$A").performClick()
            onNodeWithText("5 分钟").performClick()
            waitUntil(timeoutMillis=5_000){r.execution.state.value.focus?.taskId==A}
            onNodeWithTag("quick_pause_$A").performClick()
            waitUntil(timeoutMillis=5_000){r.execution.state.value.focus?.paused==true}
            onNodeWithTag("quick_next_$A").performClick()
            waitUntil(timeoutMillis=5_000){r.engine.state.value.items.getValue("task:$A").content().array("completedSteps").size==1}
            assertFalse(r.engine.state.value.items.getValue("task:$A").completed())
        }
    }
    @Test fun blockerSheetSavesSmallerStepAndStartsOnlyAfterSuccessfulSave() = runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {r->
            setContent {DesktopUi(r,{}, {}, {})}
            onNodeWithTag("quick_support_$A").performClick()
            onNodeWithText("不知道下一步").performClick()
            onNodeWithTag("quick_next_action").performTextReplacement("虚构：先写一个标题")
            onNodeWithTag("quick_stop_criterion").performTextReplacement("写完一个标题就停")
            onNodeWithTag("quick_save_start").performScrollTo().performClick()
            waitUntil(timeoutMillis=5_000){r.execution.state.value.focus?.taskId==A}
            val task=r.engine.state.value.items.getValue("task:$A")
            assertEquals("虚构：先写一个标题",task.content().str("nextAction"))
            assertEquals(1,task.content().array("blockerEntries").size)
            assertEquals(5,r.execution.state.value.focus?.targetMinutes)
        }
    }
    @Test fun shortBlocksKeepReadableLabelsAndDoNotOverlapInEitherScale()=runDesktopComposeUiTest(width=1280,height=900) {
        fixture().use {r->
            val day=TimelineDay.today(r.engine.state.value,Instant.now())
            r.edit {before->
                var next=TimelineActions.setSchedule(before,A,day.planId,day.start.plusSeconds(600*60L),15,TimelineScheduleBaseline.from(before,day.planId,A))
                next=TimelineActions.setSchedule(next,B,day.planId,day.start.plusSeconds(615*60L),15,TimelineScheduleBaseline.from(next,day.planId,B))
                next
            }.getOrThrow()
            setContent {DesktopUi(r,{}, {}, {})}
            onNodeWithTag("view_timeline").performClick()
            listOf("expanded","compact").forEachIndexed {index,scale->
                if(index>0)onNodeWithTag("timeline_scale_toggle").performClick()
                onNodeWithTag("timeline_slot_630").performScrollTo()
                listOf(A,B).forEach {id->
                    val label=onNodeWithTag("timeline_label_$id",useUnmergedTree=true).assertIsDisplayed()
                    val layouts=mutableListOf<TextLayoutResult>()
                    label.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
                    val layout=layouts.single()
                    assertFalse("Short title must not be truncated vertically",layout.hasVisualOverflow)
                    assertTrue(label.fetchSemanticsNode().boundsInRoot.height>=layout.size.height-1f)
                    val pixels=label.captureToImage().toPixelMap()
                    val luminance=(0 until pixels.height).flatMap {y->(0 until pixels.width).map {x->pixels[x,y].luminance()}}
                    assertTrue("The captured label must contain visible glyphs",luminance.max()-luminance.min()>.12f)
                }
                val first=onNodeWithTag("timeline_block_$A",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
                val second=onNodeWithTag("timeline_block_$B",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
                assertFalse("Adjacent short visual blocks must not cover each other",first.overlaps(second))
                val host=System.getProperty("os.name").lowercase().replace(Regex("[^a-z0-9]+"),"-")
                val output=Path.of("build","test-screenshots");Files.createDirectories(output)
                assertTrue(ImageIO.write(onNodeWithTag("desktop_root").captureToImage().toAwtImage(),"png",output.resolve("timeline-short-$host-$scale.png").toFile()))
            }
        }
    }

    private fun DesktopComposeUiTest.drag(fromTag:String,toTag:String,before:Boolean=false) {
        val source=onNodeWithTag(fromTag,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val from=source.center
        val target=onNodeWithTag(toTag).fetchSemanticsNode().boundsInRoot
        val y=when {before->target.top+6f;toTag.startsWith("timeline_slot_")->target.top+(if(fromTag.startsWith("timeline_drag_"))source.height/2f else 2f);else->target.center.y}
        mouseDrag(from,Offset(target.center.x,y))
    }
    private fun DesktopComposeUiTest.mouseDrag(from:Offset,to:Offset) {
        onNodeWithTag("desktop_root").performMouseInput {moveTo(from);press();moveTo(from+Offset(0f,12f),delayMillis=80);moveTo(to,delayMillis=250);release()}
        waitForIdle()
    }
    private fun ordered(r:DesktopRuntime,plan:String)=r.engine.state.value.items["day_plan:$plan"]?.content().array("ordered_task_ids").map {it.jsonPrimitive.content}
    private suspend fun fixture():DesktopRuntime {
        val r=DesktopRuntime(Files.createTempDirectory("step-planning-ui-"),DesktopConfig(),{_,_->},TestProtector(),NoNetwork,FixtureClock(),MobileAIClient {_,_,_->error("No AI in fixture")})
        try {
            r.initialize();r.edit {original->
                val now=Instant.now();var state=original
                listOf(A,B,C).forEachIndexed {index,id->state=StateMachine.edit(state,"task",id,ContentActions.save(null,"task","虚构行动 ${index+1}","",if(id==A)0 else 1,id,now.minusSeconds(index.toLong()),nextAction=if(id==A)"打开虚构提纲"else""))}
                val plan=ContentActions.planId(state,now)
                listOf(A,B,C).forEach {state=DayTaskActions.setIncluded(state,it,true,plan,now)}
                state
            }.getOrThrow();return r
        } catch(e:Throwable){r.close();throw e}
    }
    private companion object {
        const val A="00000000-0000-4000-8000-000000000801"
        const val B="00000000-0000-4000-8000-000000000802"
        const val C="00000000-0000-4000-8000-000000000803"
    }
}
