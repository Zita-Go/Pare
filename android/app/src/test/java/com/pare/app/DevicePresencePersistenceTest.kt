package com.pare.app

import android.content.ContextWrapper
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DevicePresencePersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val device = "80000000-0000-0000-0000-000000000001"
    private fun context() = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
        override fun getNoBackupFilesDir(): File = temporary.root
    }

    @Test fun reservedSequenceSurvivesRestartAndIsScopedByOwnerAndDevice() {
        val first = DevicePresenceFileSequenceStore(context())
        assertEquals(1L, first.next("fixture-owner", device))
        assertEquals(2L, DevicePresenceFileSequenceStore(context()).next("fixture-owner", device))
        assertEquals(1L, first.next("other-fixture-owner", device))
        assertEquals(1L, first.next("fixture-owner", "80000000-0000-0000-0000-000000000002"))
    }

    @Test fun serverHighWatermarkCanOnlyAdvanceAndCannotReuseNumbers() {
        val store = DevicePresenceFileSequenceStore(context())
        store.advance("fixture-owner", device, 10)
        store.advance("fixture-owner", device, 3)
        assertEquals(11L, DevicePresenceFileSequenceStore(context()).next("fixture-owner", device))
    }

    @Test fun damagedSequenceLedgerIsRetainedInsteadOfResetAndReuse() {
        val path = File(temporary.root, "device-presence-sequences-v1.json")
        path.writeText("{damaged fixture")
        assertThrows(Exception::class.java) { DevicePresenceFileSequenceStore(context()).next("fixture-owner", device) }
        assertEquals("{damaged fixture", path.readText())
    }

    @Test fun sharingDefaultsOffAndOnlyPersistsForTheChosenOwnerAndDevice() {
        val store = DevicePresenceSharingStore(context())
        assertFalse(store.enabled("sharing-fixture-owner", device))
        assertFalse(store.enabled(null, device))
        store.set("sharing-fixture-owner", device, true)
        assertTrue(DevicePresenceSharingStore(context()).enabled("sharing-fixture-owner", device))
        assertFalse(store.enabled("another-sharing-fixture-owner", device))
        assertFalse(store.enabled("sharing-fixture-owner", "80000000-0000-0000-0000-000000000002"))
        store.set("sharing-fixture-owner", device, false)
        assertFalse(DevicePresenceSharingStore(context()).enabled("sharing-fixture-owner", device))
    }

    @Test fun failedSharingCommitCannotGrantConsentEvenIfMemoryAndRollbackAreBroken() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val preferences = app.getSharedPreferences("presence-sharing-failed-fixture", Context.MODE_PRIVATE)
        val scope = "failed-sharing-${uid()}"
        val broken = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val editor = preferences.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { editor.putBoolean(key, value); return this }
                    override fun commit(): Boolean { editor.commit(); return false }
                    // A rollback cannot be assumed to work either.
                    override fun apply() {}
                }
            }
        }
        val store = DevicePresenceSharingStore(broken, scope)
        assertThrows(IllegalStateException::class.java) { store.set("failed-fixture-owner", device, true) }
        assertTrue(preferences.getBoolean("v1:failed-fixture-owner:$device", false))
        assertFalse(store.enabled("failed-fixture-owner", device))
        assertFalse(DevicePresenceSharingStore(preferences, scope).enabled("failed-fixture-owner", device))
        // A fresh, successful explicit choice is required before sharing can resume.
        val healthy = DevicePresenceSharingStore(preferences, scope)
        healthy.set("failed-fixture-owner", device, true)
        assertTrue(healthy.enabled("failed-fixture-owner", device))
    }

    @Test fun failedDisableAlsoDeniesSharingWithoutTrustingThePreviouslyEnabledValue() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val preferences = app.getSharedPreferences("presence-sharing-disable-fixture", Context.MODE_PRIVATE)
        val scope = "failed-disable-${uid()}"
        val healthy = DevicePresenceSharingStore(preferences, scope)
        healthy.set("disable-fixture-owner", device, true)
        val broken = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val editor = preferences.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
                    override fun commit(): Boolean = false
                    override fun apply() {}
                }
            }
        }
        assertThrows(IllegalStateException::class.java) { DevicePresenceSharingStore(broken, scope).set("disable-fixture-owner", device, false) }
        assertTrue(preferences.getBoolean("v1:disable-fixture-owner:$device", false))
        assertFalse(healthy.enabled("disable-fixture-owner", device))
        healthy.set("disable-fixture-owner", device, false)
        assertFalse(healthy.enabled("disable-fixture-owner", device))
    }
}
