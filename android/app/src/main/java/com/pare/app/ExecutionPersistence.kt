package com.pare.app

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import java.io.File
import java.util.UUID

/** No cloud backup, no shared snapshot embedding, and durable before StateFlow publication. */
class ExecutionFilePersistence(context:Context):ExecutionPersistence {
    private val path=File(context.noBackupFilesDir,"execution-v1.json")
    private val file=AtomicFile(path)
    companion object {private val lock=Any()}
    override fun load():ExecutionSnapshot?=synchronized(lock) {
        if(!path.exists() && !File(path.path+".bak").exists()) return@synchronized null
        wire.decodeFromString<ExecutionSnapshot>(file.openRead().use {it.readBytes().toString(Charsets.UTF_8)}).also {it.validate()}
    }
    override fun save(snapshot:ExecutionSnapshot)=synchronized(lock) {
        path.parentFile?.mkdirs()
        val bytes=wire.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        val output=file.startWrite()
        try {output.write(bytes);file.finishWrite(output)}
        catch(error:Exception) {file.failWrite(output);throw error}
    }
}
class AndroidExecutionClock(context:Context):ExecutionClock {
    private val resolver=context.applicationContext.contentResolver
    // If BOOT_COUNT is unavailable, a process-specific id conservatively pauses after relaunch.
    companion object {private val fallback="process-${UUID.randomUUID()}"}
    override fun now():ExecutionMoment {
        val boot=runCatching {Settings.Global.getInt(resolver,Settings.Global.BOOT_COUNT)}.getOrNull()?.toString() ?: fallback
        return ExecutionMoment(System.currentTimeMillis(),SystemClock.elapsedRealtime(),boot)
    }
}
