package com.pare.app

import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LocalTrashPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "91000000-0000-0000-0000-000000000001"
    private val now = Instant.parse("2026-10-31T09:00:00Z")
    private fun context() = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
        override fun getDatabasePath(name: String): File = File(temporary.root, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            openOrCreateDatabase(name, mode, factory)
    }
    private fun inbox(text: String = "虚构的恢复资料") = Entity("inbox", id, buildJsonObject { put("content", buildJsonObject {
        put("id", id); put("text", text); put("createdAt", now.toString())
    }) })

    @Test fun deletionAndRecoveryPayloadSurviveRepositoryReopenTogether() {
        val context = context(); val original = inbox()
        val first = PareRepository(context)
        first.update { it.copy(items = mapOf(original.key to original)) }
        val removed = first.update { LibraryActions.delete(it, "inbox", id, LibraryBaseline.from(original), now) }
        first.close()
        val reopened = PareRepository(context)
        val loaded = reopened.read()
        assertEquals(removed.localTrash, loaded.localTrash)
        assertTrue(loaded.items.getValue(original.key).deleted)
        val restored = reopened.update { LocalTrashActions.restoreOriginal(it, loaded.localTrash.single().id, now) }
        assertEquals(original.payload, restored.items[original.key]?.payload)
        reopened.close()
        val again = PareRepository(context)
        assertEquals(original.key, again.read().localTrash.single().restoredKey)
        again.close()
    }

    @Test fun oversizedTrashFailsWholeSQLiteEditAndPreservesOriginalAfterReopen() {
        val context = context(); val original = inbox("x".repeat(LocalTrashActions.MAX_BYTES))
        val repository = PareRepository(context)
        repository.update { it.copy(items = mapOf(original.key to original)) }
        assertThrows(IllegalArgumentException::class.java) {
            repository.update { LibraryActions.delete(it, "inbox", id, LibraryBaseline.from(original), now) }
        }
        repository.close()
        val reopened = PareRepository(context)
        val loaded = reopened.read()
        assertEquals(original, loaded.items[original.key]); assertTrue(loaded.localTrash.isEmpty())
        reopened.close()
    }
}
