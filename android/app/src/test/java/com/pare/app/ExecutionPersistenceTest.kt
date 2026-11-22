package com.pare.app

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ExecutionPersistenceTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun context()=object:ContextWrapper(ApplicationProvider.getApplicationContext()) {
        override fun getNoBackupFilesDir():File=temporary.root
    }
    @Test fun pendingFinishAndStableIdentitySurviveNewPersistenceInstance() {
        val local=context()
        val run=ExecutionRun("00000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000002",1_000,
            accumulatedMillis=61_000,targetMinutes=5,paused=true,pendingFinish=ExecutionFinish(62_000,2))
        ExecutionFilePersistence(local).save(ExecutionSnapshot(owner="fictional-owner",device="fictional-device",focus=run))
        val restored=ExecutionFilePersistence(local).load()!!
        assertEquals("fictional-owner",restored.owner)
        assertEquals(run.id,restored.focus!!.id)
        assertEquals(2,restored.focus!!.pendingFinish!!.minutes)
        assertEquals(61_000L,restored.focus!!.accumulatedMillis)
    }
    @Test fun unreadableLedgerIsNeverOverwrittenByLoad() {
        val file=File(temporary.root,"execution-v1.json");file.writeText("{truncated ledger")
        assertThrows(Exception::class.java) {ExecutionFilePersistence(context()).load()}
        assertEquals("{truncated ledger",file.readText())
    }
    @Test fun fallbackBootIdentityIsSharedByControllerReminderAndDialogClocks() {
        val local=context()
        android.provider.Settings.Global.putString(local.contentResolver,android.provider.Settings.Global.BOOT_COUNT,null)
        assertEquals(AndroidExecutionClock(local).now().bootId,AndroidExecutionClock(local).now().bootId)
    }
}
