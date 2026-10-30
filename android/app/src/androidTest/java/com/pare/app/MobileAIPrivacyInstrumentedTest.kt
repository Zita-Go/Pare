package com.pare.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** QA package only: exercises real Android Keystore with fictional, unusable credentials. */
@RunWith(AndroidJUnit4::class)
class MobileAIPrivacyInstrumentedTest {
    @Test fun credentialsAndRawTraceSurviveEncryptedReloadAndCanBeDeleted() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".qa"))
        val vault=MobileAIVault(context)
        vault.clear()
        try {
            val config=MobileAIConfig("https://fictional-model.invalid/v1/chat/completions","fictional-model")
            val trace=MobileAITrace("test-trace","fictional-task","2026-10-30T00:00:00Z","Fictional private input","Fictional private output",true)
            vault.save(MobileAIPrivateData(config,"fictional-unusable-api-key",listOf(trace)))
            val ciphertext=context.getSharedPreferences("mobile-ai-vault",Context.MODE_PRIVATE).getString("encrypted","")!!
            assertFalse(ciphertext.contains("fictional-unusable-api-key"))
            assertFalse(ciphertext.contains("Fictional private"))
            val loaded=MobileAIVault(context).load()
            assertEquals("fictional-unusable-api-key",loaded.apiKey)
            assertEquals(config,loaded.config)
            assertEquals(listOf(trace),loaded.history)
            vault.save(MobileAIPrivateData(config,loaded.apiKey,emptyList()))
            assertTrue(MobileAIVault(context).load().history.isEmpty())
            vault.clear()
            assertEquals("",MobileAIVault(context).load().apiKey)
        } finally {vault.clear()}
    }
}
