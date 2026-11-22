package com.step.desktop

import com.pare.app.*
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

internal interface DesktopSecretProtector {
    fun protect(bytes:ByteArray):ByteArray
    fun unprotect(bytes:ByteArray):ByteArray
}

/** Current-user DPAPI, never machine-wide; other platforms deliberately have no plaintext fallback. */
internal class DesktopDPAPI:DesktopSecretProtector {
    private val entropy="Step.Windows.private.v1".toByteArray(Charsets.UTF_8)
    private fun requireWindows()=check(Platform.isWindows()) {"安全凭据存储仅在 Windows 可用；此开发平台仍可使用离线资料"}
    override fun protect(bytes:ByteArray):ByteArray {
        requireWindows()
        return Crypt32Util.cryptProtectData(bytes,entropy,WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,"Step private data",null)
    }
    override fun unprotect(bytes:ByteArray):ByteArray {
        requireWindows()
        return Crypt32Util.cryptUnprotectData(bytes,entropy,WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,null)
    }
}

@Serializable private data class DesktopSessionData(val version:Int=1,val session:Session?=null)
internal class DesktopSessionVault(home:Path,private val protector:DesktopSecretProtector):SessionStore,AutoCloseable {
    private val file=DesktopAtomicFile(home.resolve("session.dpapi"),256*1024)
    private var closed=false
    @Synchronized override fun load():Session? {
        check(!closed) {"凭据存储已关闭"}
        return file.read()?.let {
        val decoded=wire.decodeFromString<DesktopSessionData>(protector.unprotect(it).toString(Charsets.UTF_8))
        check(decoded.version==1)
        decoded.session?.also {s->require(s.owner.isNotBlank() && s.accessToken.isNotBlank() && s.refreshToken.isNotBlank())}
        }
    }
    @Synchronized override fun save(session:Session) {
        check(!closed) {"凭据存储已关闭"}
        file.write(protector.protect(wire.encodeToString(DesktopSessionData(session=session)).toByteArray(Charsets.UTF_8)))
    }
    @Synchronized override fun clear() {
        check(!closed) {"凭据存储已关闭"}
        // Commit a protected tombstone first: even an interrupted cleanup cannot resurrect a token.
        if(file.read()==null)return
        file.write(protector.protect(wire.encodeToString(DesktopSessionData()).toByteArray(Charsets.UTF_8)),retainBackup=false)
        Files.deleteIfExists(file.backup)
    }
    @Synchronized override fun close() {closed=true}
}

@Serializable internal data class DesktopAIData(val version:Int=1,val config:MobileAIConfig=MobileAIConfig(),val apiKey:String="",val history:List<MobileAITrace> = emptyList())
internal class DesktopAIVault(home:Path,private val protector:DesktopSecretProtector) {
    private val file=DesktopAtomicFile(home.resolve("ai.dpapi"),600_000)
    @Synchronized fun load():DesktopAIData=file.read()?.let {
        wire.decodeFromString<DesktopAIData>(protector.unprotect(it).toString(Charsets.UTF_8)).also {data->check(data.version==1)}
    } ?: DesktopAIData()
    @Synchronized fun update(config:MobileAIConfig,key:String?):DesktopAIData {
        val old=load()
        val safe=if(config.endpoint.isBlank() && config.model.isBlank()) MobileAIConfig() else config.validated()
        val nextKey=key ?: old.apiKey.takeIf {old.config==safe}.orEmpty()
        require(nextKey.length<=4096 && nextKey.none {it.isISOControl()}) {"请填写有效的 API 密钥"}
        val next=old.copy(config=safe,apiKey=nextKey)
        save(next);return next
    }
    @Synchronized fun append(trace:MobileAITrace):DesktopAIData {
        val current=load()
        return current.copy(history=MobileAIHistory.append(current.history,trace.redacted(current.apiKey))).also(::save)
    }
    private fun save(data:DesktopAIData) {
        val bytes=wire.encodeToString(data).toByteArray(Charsets.UTF_8)
        require(bytes.size<=500_000)
        file.write(protector.protect(bytes))
    }
}
