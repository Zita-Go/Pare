package com.pare.app

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.Serializable

interface DevicePresenceSequenceStore {
    /** Must commit durably before returning; a lost response never permits sequence reuse. */
    fun next(owner: String, device: String): Long
    fun advance(owner: String, device: String, accepted: Long)
}

@Serializable
private data class DevicePresenceSequences(val version: Int = 1, val values: Map<String, Long> = emptyMap())

class DevicePresenceFileSequenceStore private constructor(private val path: File) : DevicePresenceSequenceStore {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "device-presence-sequences-v1.json"))
    private val file = AtomicFile(path)
    companion object { private val lock = Any() }
    private fun key(owner: String, device: String): String {
        require(owner.isNotBlank()); presenceUUID(device)
        return "$owner/$device"
    }
    private fun read(): DevicePresenceSequences {
        if (!path.exists() && !File(path.path + ".bak").exists()) return DevicePresenceSequences()
        return wire.decodeFromString<DevicePresenceSequences>(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }).also {
            require(it.version == 1 && it.values.values.all { value -> value in 0..DEVICE_PRESENCE_MAX_SEQUENCE }) { "本机运行序列无法读取，原记录已保留" }
        }
    }
    private fun write(ledger: DevicePresenceSequences) {
        path.parentFile?.mkdirs()
        val bytes = wire.encodeToString(ledger).toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    override fun next(owner: String, device: String): Long = synchronized(lock) {
        val ledger = read(); val key = key(owner, device)
        val previous = ledger.values[key] ?: 0
        require(previous < DEVICE_PRESENCE_MAX_SEQUENCE) { "本机运行序列已到上限，未发送状态" }
        val next = previous + 1
        write(ledger.copy(values = ledger.values + (key to next)))
        next
    }
    override fun advance(owner: String, device: String, accepted: Long) = synchronized(lock) {
        require(accepted in 1..DEVICE_PRESENCE_MAX_SEQUENCE)
        val ledger = read(); val key = key(owner, device)
        if (accepted > (ledger.values[key] ?: 0)) write(ledger.copy(values = ledger.values + (key to accepted)))
    }
}
