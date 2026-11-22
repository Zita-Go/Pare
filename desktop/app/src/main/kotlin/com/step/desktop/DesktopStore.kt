package com.step.desktop

import com.pare.app.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest

/** Atomic replacement is mandatory: a failed write leaves the previous complete transaction intact. */
internal class DesktopAtomicFile(val path:Path,private val limit:Long=128L*1024*1024) {
    val backup:Path=path.resolveSibling(path.fileName.toString()+".bak")
    fun read():ByteArray? {
        if(!Files.exists(path)) {
            check(!Files.exists(backup)) {"主文件缺失，恢复备份已保留；请勿清除资料目录"}
            return null
        }
        check(Files.size(path) in 1..limit) {"本机文件大小异常，原文件已保留"}
        return Files.readAllBytes(path)
    }
    fun write(bytes:ByteArray,retainBackup:Boolean=true) {
        require(bytes.size.toLong() in 1..limit)
        Files.createDirectories(path.parent)
        val temporary=Files.createTempFile(path.parent,".${path.fileName}-",".tmp")
        var backupTemporary:Path?=null
        try {
            FileChannel.open(temporary,WRITE,TRUNCATE_EXISTING).use {channel->
                val buffer=ByteBuffer.wrap(bytes)
                while(buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            if(retainBackup && Files.exists(path)) {
                backupTemporary=Files.createTempFile(path.parent,".${path.fileName}-backup-",".tmp")
                Files.copy(path,backupTemporary,REPLACE_EXISTING)
                FileChannel.open(backupTemporary,WRITE).use {it.force(true)}
                Files.move(backupTemporary,backup,ATOMIC_MOVE,REPLACE_EXISTING)
            }
            Files.move(temporary,path,ATOMIC_MOVE,REPLACE_EXISTING)
            // Unix supports directory fsync; Windows atomic rename provides the supported boundary.
            runCatching {FileChannel.open(path.parent,READ).use {it.force(true)}}
        } finally {
            Files.deleteIfExists(temporary)
            backupTemporary?.let {Files.deleteIfExists(it)}
        }
    }
}

@Serializable private data class DesktopSnapshot(val version:Int,val checksum:String,val state:SyncState)
private fun checksum(state:SyncState)=MessageDigest.getInstance("SHA-256")
    .digest(wire.encodeToString(state).toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}

/** One full SyncState transaction includes entities, conflicts, trash, outbox and pull cursor. */
class DesktopStore(home:Path):SyncStore,AutoCloseable {
    private val channel:FileChannel
    private val lock:FileLock
    private val file=DesktopAtomicFile(home.resolve("sync-state.json"))
    private val mutable=MutableStateFlow(SyncState())
    override val snapshots:StateFlow<SyncState> = mutable
    private var loaded=false
    private var closed=false
    init {
        Files.createDirectories(home)
        channel=FileChannel.open(home.resolve("step.lock"),CREATE,WRITE)
        lock=try {channel.tryLock() ?: error("此资料库已由另一个 Step 进程打开")}
        catch(error:Exception) {channel.close();throw IllegalStateException("此资料库已由另一个 Step 进程打开，或无法取得独占锁",error)}
    }
    @Synchronized override fun read():SyncState {
        check(!closed) {"资料库已关闭"}
        if(!loaded) {
            val bytes=file.read()
            val next=if(bytes==null) mutable.value.also(::persist) else {
                val decoded=wire.decodeFromString<DesktopSnapshot>(bytes.toString(Charsets.UTF_8))
                check(decoded.version==1 && decoded.checksum==checksum(decoded.state)) {"本机快照校验失败，原文件和备份已保留"}
                decoded.state.also(::validate)
            }
            mutable.value=next;loaded=true
        }
        return mutable.value
    }
    @Synchronized override fun update(transform:(SyncState)->SyncState):SyncState {
        val old=read()
        val next=transform(old)
        validate(next)
        if(next!=old) {persist(next);mutable.value=next}
        return next
    }
    private fun validate(state:SyncState) {
        require(state.device.isNotBlank() && state.cursor>=0 && (state.owner==null || state.owner!!.isNotBlank()))
        require(state.items.all {(key,item)->key==item.key})
        state.pending?.let {require(it.device==state.device && it.operation.isNotBlank() && it.mutations.isNotEmpty())}
    }
    private fun persist(state:SyncState)=file.write(wire.encodeToString(DesktopSnapshot(1,checksum(state),state)).toByteArray(Charsets.UTF_8))
    @Synchronized internal fun saveRollback(expected:SyncState):Path {
        check(read()==expected) {"本机内容已改变，请重新预览"}
        val path=file.path.resolveSibling("sync-rollback-${uid()}.json")
        DesktopAtomicFile(path).write(wire.encodeToString(DesktopSnapshot(1,checksum(expected),expected)).toByteArray(Charsets.UTF_8),retainBackup=false)
        return path
    }
    @Synchronized internal fun recoverAutomaticBackup() {
        check(!closed && !loaded) {"当前资料已正常读取；恢复自动备份仅用于无法打开的资料库"}
        val bytes=DesktopAtomicFile(file.backup).read() ?: error("没有可恢复的自动备份")
        val recovered=wire.decodeFromString<DesktopSnapshot>(bytes.toString(Charsets.UTF_8))
        check(recovered.version==1 && recovered.checksum==checksum(recovered.state)) {"自动备份也未通过校验；所有原文件已保留"}
        validate(recovered.state)
        if(Files.exists(file.path)) {
            val damaged=file.path.resolveSibling("sync-damaged-${uid()}.json")
            Files.copy(file.path,damaged)
        }
        file.write(bytes,retainBackup=false)
    }
    @Synchronized override fun close() {
        if(closed)return
        closed=true
        try {lock.release()} finally {channel.close()}
    }
}

internal class DesktopExecutionPersistence(home:Path):ExecutionPersistence {
    private val file=DesktopAtomicFile(home.resolve("execution.json"))
    override fun load():ExecutionSnapshot?=file.read()?.let {wire.decodeFromString<ExecutionSnapshot>(it.toString(Charsets.UTF_8)).also {snapshot->snapshot.validate()}}
    override fun save(snapshot:ExecutionSnapshot) {snapshot.validate();file.write(wire.encodeToString(snapshot).toByteArray(Charsets.UTF_8))}
}

internal class DesktopClock:ExecutionClock {
    private val process="process-${uid()}"
    private val origin=System.nanoTime()
    override fun now()=ExecutionMoment(System.currentTimeMillis(),((System.nanoTime()-origin)/1_000_000).coerceAtLeast(0),process)
}
