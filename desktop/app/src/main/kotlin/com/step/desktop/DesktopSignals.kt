package com.step.desktop

import com.pare.app.*
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path

@Serializable internal data class DesktopSignal(val token:String,val action:String)
internal object DesktopSignals {
    fun activation(uri:String,scheme:String):DesktopSignal {
        require(uri.length<=1024)
        val value=URI(uri)
        require(value.scheme==scheme && value.host=="notification" && value.userInfo==null && value.fragment==null && value.path.orEmpty().isEmpty())
        val fields=value.rawQuery.orEmpty().split('&').map {part->part.split('=',limit=2).let {require(it.size==2);URLDecoder.decode(it[0],"UTF-8") to URLDecoder.decode(it[1],"UTF-8")}}
        require(fields.size==2 && fields.map {it.first}.toSet()==setOf("token","action"))
        return DesktopSignal(fields.toMap().getValue("token"),fields.toMap().getValue("action")).also(::validate)
    }
    fun validate(signal:DesktopSignal) {require(signal.token.matches(Regex("[A-Za-z0-9:_-]{1,200}")) && signal.action in setOf("open","complete","snooze","deliver"))}
    fun enqueue(home:Path,signal:DesktopSignal) {
        validate(signal)
        val dir=home.resolve("notification-inbox");Files.createDirectories(dir)
        DesktopAtomicFile(dir.resolve("${uid()}.json"),2048).write(wire.encodeToString(signal).toByteArray(),retainBackup=false)
    }
    fun drain(home:Path,consumer:(DesktopSignal)->Unit) {
        val dir=home.resolve("notification-inbox")
        if(!Files.isDirectory(dir))return
        Files.list(dir).use {paths->paths.filter {it.fileName.toString().endsWith(".json")}.limit(100).forEach {path->
            try {
                require(Files.size(path)<=2048)
                val signal=wire.decodeFromString<DesktopSignal>(Files.readString(path));validate(signal);consumer(signal)
            } finally {Files.deleteIfExists(path)}
        }}
    }
}
