package com.step.desktop

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream

class DesktopSystemTest {
    @Test fun browserTitleIdentityIsAllowlistedSaltedAndClearsRawBuffer() {
        val identity=DesktopBrowserIdentity()
        val title="Fictional private report - Chrome"
        val buffer=title.toCharArray()
        val token=identity.token("chrome.exe",buffer,buffer.size)
        assertNotNull(token);assertFalse(token!!.contains("Fictional"))
        assertTrue(buffer.all {it=='\u0000'})
        assertEquals(token,identity.token("CHROME.EXE",title.toCharArray(),title.length))
        assertNotEquals(token,DesktopBrowserIdentity().token("chrome.exe",title.toCharArray(),title.length))
        assertNull(identity.token("notepad.exe",title.toCharArray(),title.length))
        identity.reset()
        assertNotEquals(token,identity.token("chrome.exe",title.toCharArray(),title.length))
    }
    @Test fun failedDownloadDirectoryPreparationClearsBusyAndCanRetry() {
        val home=Files.createTempDirectory("step-download-retry")
        val installer="fictional verified installer".toByteArray()
        val metadata=buildJsonArray {add(buildJsonObject {
            put("draft",false);put("body","fictional update")
            put("assets",buildJsonArray {add(buildJsonObject {
                put("name","Step-0.2.0.exe");put("browser_download_url","https://github.com/Zita-Go/Pare/releases/download/windows-0.2.0/Step-0.2.0.exe")
                put("size",installer.size);put("digest","sha256:"+DesktopBackup.fingerprint(installer))
            })})
        })}.toString().toByteArray()
        val updates=DesktopUpdates(home,"0.1.0") {url,isMetadata->
            object:HttpURLConnection(URL(url)) {
                override fun connect()=Unit
                override fun disconnect()=Unit
                override fun usingProxy()=false
                override fun getInputStream()=ByteArrayInputStream(if(isMetadata)metadata else installer)
            }
        }
        updates.check()
        Files.writeString(home.resolve("updates"),"fictional file blocking the download directory")
        assertThrows(java.io.IOException::class.java) {updates.download()}
        assertFalse("An IO preparation failure must leave the download retryable",updates.state.value.busy)
        assertFalse(updates.state.value.downloaded)
        Files.delete(home.resolve("updates"))
        updates.download()
        assertFalse(updates.state.value.busy);assertTrue(updates.state.value.downloaded)
        assertArrayEquals(installer,Files.readAllBytes(updates.installer()))
    }
    @Test fun windowsScriptUsesRemoteSignedProcessScopeUnderRestrictedParent() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows",true))
        val home=Files.createTempDirectory("step-policy-test")
        val script=home.resolve("policy.ps1");val input=home.resolve("input.json");val output=home.resolve("policy.txt")
        Files.writeString(script,"param([string]\u0024InputFile)\r\n\u0024ErrorActionPreference = 'Stop'\r\nGet-ExecutionPolicy\r\n")
        Files.writeString(input,"{}")
        val builder=desktopPowerShellProcess(script,input).redirectErrorStream(true).redirectOutput(output.toFile())
        builder.environment()["PSExecutionPolicyPreference"]="Restricted"
        val process=builder.start()
        try {
            assertTrue(process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("Local script should execute under the child-process policy: ${Files.readString(output)}",0,process.exitValue())
            assertEquals("RemoteSigned",Files.readString(output).trim())
        } finally {if(process.isAlive)process.destroyForcibly()}
    }
    @Test fun windowsScriptIgnoresPowerShellSevenModulesInheritedThroughJava() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows",true))
        val home=Files.createTempDirectory("step-module-environment-test")
        val script=home.resolve("policy.ps1");val input=home.resolve("input.json")
        val output=home.resolve("policy.txt");val probeOutput=home.resolve("probe.txt")
        Files.writeString(script,"param([string]\u0024InputFile)\r\n\u0024ErrorActionPreference = 'Stop'\r\nGet-ExecutionPolicy\r\n")
        Files.writeString(input,"{}")
        val inherited=home.resolve("inherited-pwsh7-modules")
        val incompatible=inherited.resolve("Microsoft.PowerShell.Security")
        Files.createDirectories(incompatible)
        Files.writeString(incompatible.resolve("Microsoft.PowerShell.Security.psd1"),
            "@{ RootModule='incompatible.psm1'; ModuleVersion='99.0'; PowerShellVersion='7.0'; FunctionsToExport=@('Get-ExecutionPolicy') }")
        Files.writeString(incompatible.resolve("incompatible.psm1"),"throw 'The inherited fixture module must not load'")
        val classpath=listOf(DesktopPowerShellEnvironmentProbe::class.java,Class.forName("com.step.desktop.DesktopWindowsKt"),Unit::class.java)
            .map {java.nio.file.Path.of(it.protectionDomain.codeSource.location.toURI()).toString()}.distinct().joinToString(java.io.File.pathSeparator)
        val javaExecutable=java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe")
        val builder=ProcessBuilder(javaExecutable.toString(),"-cp",classpath,"com.step.desktop.DesktopPowerShellEnvironmentProbe",script.toString(),input.toString(),output.toString())
            .redirectErrorStream(true).redirectOutput(probeOutput.toFile())
        // Poison the intermediate Java process, not the already-sanitized PowerShell builder.
        builder.environment()["PSModulePath"]=inherited.toString()
        val process=builder.start()
        try {
            assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS))
            val details=Files.readString(probeOutput)+if(Files.exists(output))Files.readString(output)else""
            assertEquals("Windows PowerShell must load its own built-in modules: $details",0,process.exitValue())
            assertEquals("RemoteSigned",Files.readString(output).trim())
        } finally {if(process.isAlive)process.destroyForcibly()}
    }
    @Test fun updateChecksWaitOneDayAfterSuccessAndBackOffFailures() {
        val first=DesktopUpdateCheckLedger().completed(1_000,false)
        assertEquals(901_000L,first.nextCheckAt)
        val second=first.completed(2_000,false)
        assertEquals(1_802_000L,second.nextCheckAt)
        val success=second.completed(3_000,true)
        assertEquals(86_403_000L,success.nextCheckAt)
        assertEquals(0,success.failures)
    }
    @Test fun notificationActivationRejectsExtraOrUnknownArguments() {
        assertEquals(DesktopSignal("abc:123:","snooze"),DesktopSignals.activation("step-fixture://notification?token=abc%3A123%3A&action=snooze","step-fixture"))
        assertThrows(IllegalArgumentException::class.java) {DesktopSignals.activation("step-fixture://notification?token=a&action=deleteAll","step-fixture")}
        assertThrows(IllegalArgumentException::class.java) {DesktopSignals.activation("step-fixture://notification?token=a&action=open&action=complete","step-fixture")}
    }
    @Test fun updateCandidateRequiresOfficialURLDigestAndNewerWindowsInstaller() {
        fun release(url:String,digest:String,version:String="0.2.0")=buildJsonArray {add(buildJsonObject {
            put("draft",false);put("prerelease",true);put("tag_name","windows-$version");put("body","fictional release")
            put("assets",buildJsonArray {add(buildJsonObject {put("name","Step-$version.exe");put("browser_download_url",url);put("size",100);put("digest",digest)})})
        })}.toString()
        val official="https://github.com/Zita-Go/Pare/releases/download/windows-0.2.0/Step-0.2.0.exe"
        assertEquals("0.2.0",DesktopUpdates.select(release(official,"sha256:"+"a".repeat(64)),"0.1.0")!!.version)
        assertNull(DesktopUpdates.select(release("https://untrusted.invalid/Step.exe","sha256:"+"a".repeat(64)),"0.1.0"))
        assertNull(DesktopUpdates.select(release(official,""),"0.1.0"))
        assertNull(DesktopUpdates.select(release(official,"sha256:"+"a".repeat(64)),"0.3.0"))
    }
    @Test fun mutatedDownloadedInstallerIsRejectedBeforeLaunching() {
        val file=Files.createTempFile("step-update-fixture",".exe")
        Files.writeString(file,"fictional installer")
        val candidate=DesktopUpdateCandidate("0.2.0","","https://github.com/Zita-Go/Pare/releases/download/v0.2.0/Step-0.2.0.exe",Files.size(file),DesktopBackup.fingerprint(Files.readAllBytes(file)))
        DesktopUpdates.verify(file,candidate)
        Files.writeString(file,"replaced")
        assertThrows(IllegalArgumentException::class.java) {DesktopUpdates.verify(file,candidate)}
    }
    @Test fun attentionSignalsAreLocalRateLimitedAndSnoozable() {
        val detector=DesktopAttentionPolicy()
        assertNull(detector.observe(0,0,1,false))
        var message:String?=null
        for(i in 1..14)message=detector.observe(i*3_000L,0,1+(i%2).toLong(),true) ?: message
        assertNotNull(message)
        assertNull(detector.observe(45_000,0,99,true))
        detector.snooze(46_000,60)
        assertNull(detector.observe(50_000,0,100,true))
    }
    @Test fun ordinaryWindowTraversalIsNotTreatedAsAttentionDrift() {
        val detector=DesktopAttentionPolicy()
        for(i in 1..20)assertNull(detector.observe(i*3_000L,0,i.toLong(),true))
    }
    @Test fun browserRevisitsWithoutFocusNeedAnActionableTaskAndNoRecentProgress() {
        val detector=DesktopAttentionPolicy()
        var hint:String?=null
        for(i in 0..15)hint=detector.observe(610_000+i*3_000L,0,7,false,"digest-${i%2}",true,0) ?: hint
        assertNotNull("Repeated same-window browser revisits can prompt without a focus timer",hint)
        val fresh=DesktopAttentionPolicy()
        for(i in 0..15)assertNull(fresh.observe(610_000+i*3_000L,0,7,false,"digest-${i%2}",true,600_000))
        val noTasks=DesktopAttentionPolicy()
        for(i in 0..15)assertNull(noTasks.observe(610_000+i*3_000L,0,7,false,"digest-${i%2}",false,0))
    }
    @Test fun clearingAttentionSignalsDiscardsPriorWindowAndBrowserObservations() {
        val detector=DesktopAttentionPolicy()
        for(i in 0..9)assertNull(detector.observe(610_000+i*3_000L,0,7,false,"digest-${i%2}",true,0))
        detector.clearSignals()
        for(i in 0..4)assertNull(detector.observe(650_000+i*3_000L,0,7,false,"digest-${i%2}",true,0))
    }
}

/** Test-only intermediate JVM reproduces pwsh7 -> Java -> Windows PowerShell environment inheritance. */
internal object DesktopPowerShellEnvironmentProbe {
    @JvmStatic fun main(args:Array<String>) {
        val builder=desktopPowerShellProcess(java.nio.file.Path.of(args[0]),java.nio.file.Path.of(args[1]))
            .redirectErrorStream(true).redirectOutput(java.nio.file.Path.of(args[2]).toFile())
        builder.environment()["PSExecutionPolicyPreference"]="Restricted"
        val process=builder.start()
        val exit=try {if(process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS))process.exitValue() else 124}
            finally {if(process.isAlive)process.destroyForcibly()}
        kotlin.system.exitProcess(exit)
    }
}
