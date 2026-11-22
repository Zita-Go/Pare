package com.step.desktop

import com.pare.app.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

/** Test-only substitute for DPAPI; secrets still never enter plaintext files. */
internal class TestProtector:DesktopSecretProtector {
    private val key=KeyGenerator.getInstance("AES").apply {init(128)}.generateKey()
    override fun protect(bytes:ByteArray):ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE,key);iv+doFinal(bytes)
    }
    override fun unprotect(bytes:ByteArray):ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE,key,javax.crypto.spec.GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        doFinal(bytes.copyOfRange(12,bytes.size))
    }
}

class DesktopSecurityTest {
    @Test fun clearingAIHistoryPreservesServiceAndRemovesRecoverableOldTraces()=kotlinx.coroutines.runBlocking {
        val home=Files.createTempDirectory("step-history-delete");val protector=TestProtector()
        val vault=DesktopAIVault(home,protector)
        val config=MobileAIConfig("https://fixture.invalid/chat","model")
        vault.update(config,"fictional-key")
        vault.append(MobileAITrace("trace-one","task","2026-11-22T00:00:00Z","fictional private request","response",true))
        DesktopRuntime(home,DesktopConfig(),{_,_->},protector,NoNetwork,FixtureClock(),MobileAIClient()).use {r->
            r.initialize();r.clearAIHistory().getOrThrow()
            assertTrue(r.aiHistory.value.isEmpty());assertTrue(r.aiSettings.value.hasKey)
            assertEquals(config,r.aiSettings.value.config)
        }
        assertTrue(vault.load().history.isEmpty())
        assertFalse(Files.exists(home.resolve("ai.dpapi.bak")))
    }
    @Test fun feedbackUpdatesOneRequestWithoutLosingOtherFeedback()=kotlinx.coroutines.runBlocking {
        val home=Files.createTempDirectory("step-feedback-test")
        DesktopRuntime(home,DesktopConfig(),{_,_->},TestProtector(),NoNetwork,FixtureClock(),MobileAIClient()).use {r->
            r.initialize()
            val a=DesktopAIFeedback("a","request-a","task","helpful","fictional","2026-11-22T00:00:00Z")
            val b=DesktopAIFeedback("b","request-b","task","unhelpful","fictional","2026-11-22T00:00:00Z")
            r.recordAIFeedback(a).getOrThrow();r.recordAIFeedback(b).getOrThrow()
            r.recordAIFeedback(a.copy(kind="unhelpful")).getOrThrow()
            assertEquals(2,r.aiContext.value.feedback.size)
            assertEquals("unhelpful",r.aiContext.value.feedback.first {it.requestId=="request-a"}.kind)
            r.deleteAIFeedback("a").getOrThrow()
            assertEquals(listOf(b),r.aiContext.value.feedback)
        }
    }
    @Test fun closedRuntimeCannotClearCredentialsOwnedByReopenedInstance() {
        val home=Files.createTempDirectory("step-vault-lifetime-test");val protector=TestProtector()
        val old=DesktopSessionVault(home,protector)
        val session=Session("fictional-owner","fictional@example.invalid","fictional-access","fictional-refresh",4_000_000_000)
        old.save(session)
        old.close()
        assertThrows(IllegalStateException::class.java) {old.clear()}
        assertEquals(session,DesktopSessionVault(home,protector).load())
    }
    @Test fun platformProtectorUsesDPAPIOrRefusesInsteadOfPlaintextFallback() {
        val protector=DesktopDPAPI()
        val secret="fictional-platform-test".toByteArray()
        if(com.sun.jna.Platform.isWindows()) {
            val encrypted=protector.protect(secret)
            assertFalse(encrypted.contentEquals(secret))
            assertArrayEquals(secret,protector.unprotect(encrypted))
        } else {
            assertThrows(IllegalStateException::class.java) {protector.protect(secret)}
        }
    }
    @Test fun tokenRoundTripIsEncryptedAndLogoutCannotRestoreBackup() {
        val home=Files.createTempDirectory("step-vault-test")
        val protector=TestProtector()
        val session=Session("fictional-owner","fictional@example.invalid","fictional-access-secret","fictional-refresh-secret",4_000_000_000)
        val vault=DesktopSessionVault(home,protector)
        vault.save(session);vault.save(session.copy(expiresAt=4_000_000_001))
        assertEquals(session.copy(expiresAt=4_000_000_001),DesktopSessionVault(home,protector).load())
        Files.list(home).use {paths->paths.filter {Files.isRegularFile(it)}.forEach {
            assertFalse(Files.readAllBytes(it).toString(Charsets.UTF_8).contains("fictional-access-secret"))
        }}
        vault.clear()
        assertNull(DesktopSessionVault(home,protector).load())
        assertFalse(Files.exists(home.resolve("session.dpapi.bak")))
    }

    @Test fun changingModelOrEndpointNeverReusesPreviousServiceKey() {
        val home=Files.createTempDirectory("step-ai-scope-test")
        val vault=DesktopAIVault(home,TestProtector())
        val config=MobileAIConfig("https://fixture.invalid/chat/completions","fictional-model")
        vault.update(config,"fictional-api-key")
        assertEquals("fictional-api-key",vault.update(config,null).apiKey)
        assertEquals("",vault.update(config.copy(model="other-model"),null).apiKey)
        vault.update(config,"fictional-api-key")
        assertEquals("",vault.update(config.copy(endpoint="https://other.invalid/chat/completions"),null).apiKey)
    }

    @Test fun unsupportedPlatformRefusesSecretPersistence() {
        val home=Files.createTempDirectory("step-no-fallback-test")
        val denied=object:DesktopSecretProtector {
            override fun protect(bytes:ByteArray):ByteArray=throw IllegalStateException("DPAPI unavailable")
            override fun unprotect(bytes:ByteArray):ByteArray=throw IllegalStateException("DPAPI unavailable")
        }
        assertThrows(IllegalStateException::class.java) {DesktopAIVault(home,denied).update(MobileAIConfig("https://fixture.invalid/chat","model"),"secret")}
        assertFalse(Files.exists(home.resolve("ai.dpapi")))
    }
}
