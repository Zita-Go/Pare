package com.step.desktop

import com.pare.app.*
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable private data class DesktopPresenceData(val version:Int,val enabled:Map<String,Boolean>,val sequences:Map<String,Long>)
internal class DesktopPresenceStore(home:Path):DevicePresenceSequenceStore {
    private val file=DesktopAtomicFile(home.resolve("presence.json"),1024*1024)
    private fun read()=file.read()?.let {wire.decodeFromString<DesktopPresenceData>(it.toString(Charsets.UTF_8)).also {data->
        check(data.version==1 && data.sequences.values.all {value->value in 0..9_007_199_254_740_991L}) {"运行共享记录无法读取，原文件已保留"}
    }} ?: DesktopPresenceData(1,emptyMap(),emptyMap())
    private fun write(data:DesktopPresenceData)=file.write(wire.encodeToString(data).toByteArray(Charsets.UTF_8))
    private fun key(owner:String,device:String):String {require(owner.isNotBlank() && device.isNotBlank());return "$owner/$device"}
    @Synchronized fun enabled(owner:String?,device:String):Boolean=owner!=null && read().enabled[key(owner,device)]==true
    @Synchronized fun set(owner:String,device:String,enabled:Boolean) {
        val old=read();write(old.copy(enabled=old.enabled+(key(owner,device) to enabled)))
    }
    @Synchronized override fun next(owner:String,device:String):Long {
        val old=read();val key=key(owner,device);val next=(old.sequences[key] ?: 0)+1
        require(next in 1..9_007_199_254_740_991L)
        write(old.copy(sequences=old.sequences+(key to next)));return next
    }
    @Synchronized override fun advance(owner:String,device:String,accepted:Long) {
        require(accepted in 1..9_007_199_254_740_991L)
        val old=read();val key=key(owner,device)
        if(accepted>(old.sequences[key] ?: 0))write(old.copy(sequences=old.sequences+(key to accepted)))
    }
}
