package com.pare.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.serialization.json.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RepositoryTest {
    @Test fun savedContentAndDirtyStateSurviveDatabaseReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("pare.db")
        val repo = PareRepository(context)
        repo.update { StateMachine.edit(it,"inbox","test-id",buildJsonObject { put("content",buildJsonObject { put("text","offline") }) }) }
        repo.close()
        val reopened = PareRepository(context)
        assertTrue(reopened.read().items.getValue("inbox:test-id").dirty)
        assertEquals("offline",reopened.read().items.getValue("inbox:test-id").payload!!.getValue("content").jsonObject.getValue("text").jsonPrimitive.content)
        reopened.close()
    }
    @Test fun failedTransactionLeavesContentAndOutboxUnchanged() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("pare.db")
        val repo = PareRepository(context)
        val before = repo.read()
        assertThrows(IllegalStateException::class.java) { repo.update { StateMachine.edit(it,"inbox","test-id",buildJsonObject {}); error("failed operation") } }
        assertEquals(before,repo.read())
        repo.close()
    }
    @Test fun snapshotLargerThanCursorWindowSurvivesReopen() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("pare.db")
        val repo=PareRepository(context)
        val large="fictional".repeat(80_000)
        repo.update {initial ->
            var s=initial
            repeat(4) {index ->s=StateMachine.edit(s,"inbox","large-$index",buildJsonObject {put("content",buildJsonObject {put("text",large)})})}
            s
        }
        repo.close()
        val reopened=PareRepository(context)
        assertEquals(4,reopened.read().items.size)
        assertEquals(large,reopened.read().items.getValue("inbox:large-3").payload!!.getValue("content").jsonObject.getValue("text").jsonPrimitive.content)
        reopened.close()
    }

    @Test fun largeV1SnapshotMigratesWithoutLoadingAnOversizedCursorRow() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("pare.db")
        val text="迁移🌱".repeat(250_000)
        val original=StateMachine.edit(SyncState(),"inbox","migration",buildJsonObject {put("content",buildJsonObject {put("text",text)})})
        val old=context.openOrCreateDatabase("pare.db",0,null)
        old.execSQL("CREATE TABLE snapshot (id INTEGER PRIMARY KEY, content TEXT NOT NULL)")
        old.execSQL("INSERT INTO snapshot (id,content) VALUES (1,?)",arrayOf(wire.encodeToString(original)))
        old.version=1;old.close()
        val migrated=PareRepository(context)
        assertEquals(original,migrated.read())
        migrated.close()
    }

}
