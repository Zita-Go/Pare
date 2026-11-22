package com.step.desktop

import com.pare.app.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DesktopLibrarySearchTest {
    @Test fun completedStepTextIsSearchableWithinTheSelectedTaskScope() {
        val id="00000000-0000-4000-8000-000000001599";val at=Instant.parse("2026-11-22T08:00:00Z")
        var s=StateMachine.edit(SyncState(),"task",id,ContentActions.save(null,"task","虚构研究","",1,id,at))
        s=NextStepActions.save(s,id,"整理旧稿素材","找到三份参考",SmallStepBaseline.from(s.items.getValue("task:$id")),at)
        s=NextStepActions.complete(s,id,"00000000-0000-4000-8000-000000001600",SmallStepBaseline.from(s.items.getValue("task:$id")),at)
        assertEquals(listOf(id),DesktopLibraryQuery.find(s,"旧稿 参考",LibraryScope.TASKS,null).map {it.id})
        assertTrue(DesktopLibraryQuery.find(s,"旧稿",LibraryScope.IDEAS,null).isEmpty())
        assertTrue(DesktopLibraryQuery.find(s,"不相关词",LibraryScope.ALL,null).isEmpty())
    }
}
