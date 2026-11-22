package com.step.desktop

import com.pare.app.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@ConsistentCopyVisibility
data class DesktopBackupPreview internal constructor(
    val id:String,val source:String,val entityCount:Int,val newCount:Int,val changedCount:Int,
    val deletedCount:Int,val trashCount:Int,
    internal val fingerprint:String,internal val baseline:SyncState,internal val entities:List<Entity>,
    internal val trash:List<LocalTrashEntry>,val restoresPreviousImport:Boolean=false
)

internal object DesktopBackup {
    private const val MAX_BYTES=32*1024*1024
    private val kinds=setOf("task","inbox","idea","goal","profile","day_plan")
    private const val BEFORE_IMPORT_PREFIX="sync-content-before-import-"
    fun isBeforeImportFile(path:Path,home:Path):Boolean = path.toAbsolutePath().normalize().parent==home.toAbsolutePath().normalize() &&
        path.fileName.toString().startsWith(BEFORE_IMPORT_PREFIX) && path.fileName.toString().endsWith(".json")
    fun latestBeforeImport(home:Path):Path? {
        if(!Files.isDirectory(home))return null
        return Files.list(home).use {files->files.filter {Files.isRegularFile(it) && isBeforeImportFile(it,home)}
            .max(compareBy<Path> {Files.getLastModifiedTime(it)}).orElse(null)}
    }
    fun prepareBeforeImport(home:Path,before:SyncState,after:SyncState):Path {
        // Explicit tombstones undo newly imported IDs when this content backup is merged back.
        val additions=after.items.values.filter {it.key !in before.items}.map {Entity(it.kind,it.id,deleted=true)}
        val content=buildJsonObject {
            put("format","step-content-backup");put("version",1)
            put("entities",JsonArray((before.items.values+additions).map {entity->buildJsonObject {
                put("kind",entity.kind);put("id",entity.id);put("deleted",entity.deleted);put("payload",entity.payload ?: JsonNull)
            }}))
            put("localTrash",wire.encodeToJsonElement(before.localTrash))
        }.toString().toByteArray(Charsets.UTF_8)
        require(content.size<=MAX_BYTES && before.items.size+additions.size<=10_000) {"导入前内容过大，无法建立可再次导入的恢复备份；未更改资料"}
        // Prepared files deliberately cannot be discovered as successful recovery points.
        return home.resolve("$BEFORE_IMPORT_PREFIX${uid()}.prepared").also {DesktopAtomicFile(it).write(content,retainBackup=false)}
    }
    fun publishBeforeImport(prepared:Path):Path {
        val name=prepared.fileName.toString()
        require(name.startsWith(BEFORE_IMPORT_PREFIX) && name.endsWith(".prepared"))
        val published=prepared.resolveSibling(name.removeSuffix(".prepared")+".json")
        Files.move(prepared,published,ATOMIC_MOVE)
        runCatching {java.nio.channels.FileChannel.open(published.parent,java.nio.file.StandardOpenOption.READ).use {it.force(true)}}
        return published
    }
    fun bytes(path:Path):ByteArray {
        require(Files.isRegularFile(path) && Files.size(path) in 1..MAX_BYTES.toLong()) {"请选择不超过32 MB的 Step 内容备份"}
        return Files.newInputStream(path).use {it.readNBytes(MAX_BYTES+1)}.also {require(it.size<=MAX_BYTES)}
    }
    fun fingerprint(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    fun preview(path:Path,state:SyncState):DesktopBackupPreview {
        val bytes=bytes(path);val text=bytes.toString(Charsets.UTF_8)
        rejectDuplicateKeys(text)
        val root=wire.parseToJsonElement(text).jsonObject
        require(root.keys==setOf("format","version","entities","localTrash") && root["format"]?.jsonPrimitive?.content=="step-content-backup" && root["version"]==JsonPrimitive(1)) {"不是受支持的内容备份；不会导入凭据或运行状态"}
        val values=root.getValue("entities").jsonArray
        require(values.size<=10_000) {"备份内容过多"}
        val entities=values.map {value->
            val obj=value.jsonObject
            require(obj.keys.all {it in setOf("kind","id","deleted","payload")} && obj.keys.containsAll(setOf("kind","id","deleted","payload"))) {"备份存在冲突或未知资料字段，请先在原资料库处理"}
            val kind=obj.getValue("kind").jsonPrimitive.content;val id=obj.getValue("id").jsonPrimitive.content
            val deleted=obj.getValue("deleted").jsonPrimitive.boolean
            val payload=obj.getValue("payload").takeUnless {it==JsonNull}?.jsonObject
            require(kind in kinds && id.matches(Regex("[A-Za-z0-9_:@+./-]{1,160}")) && deleted==(payload==null))
            if(kind in setOf("task","inbox","idea","goal"))require(UUID.fromString(id).toString()==id)
            if(kind=="profile")require(id=="profile")
            if(kind=="day_plan") {
                require(id.count {it=='@'}==1)
                require(java.time.LocalDate.parse(id.substringBefore('@')).toString()==id.substringBefore('@'))
                java.time.ZoneId.of(id.substringAfter('@'))
            }
            payload?.let {PayloadValidation.validate(kind,id,it)}
            Entity(kind,id,payload,deleted=deleted)
        }
        require(entities.map {it.key}.distinct().size==entities.size) {"备份包含重复内容标识"}
        val trash=wire.decodeFromJsonElement<List<LocalTrashEntry>>(root.getValue("localTrash"))
        require(trash.size<=LocalTrashActions.MAX_ENTRIES && wire.encodeToString(trash).toByteArray().size<=LocalTrashActions.MAX_BYTES)
        require(trash.map {it.id}.distinct().size==trash.size)
        trash.forEach {entry->
            require(UUID.fromString(entry.id).toString()==entry.id && UUID.fromString(entry.copyId).toString()==entry.copyId)
            Instant.parse(entry.deletedAt)
            require(entry.before.kind in setOf("task","inbox","idea","goal") && !entry.before.deleted && entry.before.payload!=null && entry.before.conflict==null && entry.tombstone.conflict==null)
            require(entry.before.key==entry.tombstone.key && entry.tombstone.deleted && entry.tombstone.payload==null)
            PayloadValidation.validate(entry.before.kind,entry.before.id,entry.before.payload!!)
            entry.restoredAt?.let(Instant::parse)
        }
        return DesktopBackupPreview(uid(),path.toAbsolutePath().normalize().toString(),entities.size,
            entities.count {it.key !in state.items},entities.count {state.items[it.key]?.let {old->old.payload!=it.payload || old.deleted!=it.deleted}==true},
            entities.count {it.deleted},trash.size,fingerprint(bytes),state,entities,trash)
    }
    fun apply(preview:DesktopBackupPreview,current:SyncState):SyncState {
        check(current==preview.baseline) {"预览后本机内容已改变，请重新预览"}
        check(current.pending==null && current.items.values.none {it.conflict!=null || it.conflictGroup.isNotEmpty()}) {"请先处理同步中的请求和冲突，再导入备份"}
        check(fingerprint(bytes(Path.of(preview.source)))==preview.fingerprint) {"备份文件已改变，请重新预览"}
        var next=current
        for(item in preview.entities) if(current.items[item.key]?.let {it.payload==item.payload && it.deleted==item.deleted}!=true)
            next=StateMachine.edit(next,item.kind,item.id,item.payload,item.deleted)
        // Recovery is conservative: later deletions and their restore receipts are user data too.
        // Older snapshots may fill missing IDs, but cannot clear or rewrite an existing entry.
        val entries=next.localTrash.toMutableList()
        preview.trash.forEach {entry->
            val imported=entry.copy(owner=current.owner,device=current.device,
                tombstone=next.items[entry.before.key]?.takeIf {it.deleted} ?: entry.tombstone)
            val old=entries.firstOrNull {it.id==entry.id}
            require(old==null || old.before==imported.before) {"回收记录标识冲突，未导入"}
            if(old==null)entries+=imported
        }
        require(entries.size<=LocalTrashActions.MAX_ENTRIES && wire.encodeToString(entries).toByteArray().size+entries.size*128<=LocalTrashActions.MAX_BYTES) {"导入后回收站超出容量，请先整理"}
        return next.copy(localTrash=entries)
    }
    private fun rejectDuplicateKeys(json:String) {
        val stack=mutableListOf<MutableSet<String>?>();var quoted=false;var escaped=false;var start=0
        json.forEachIndexed {index,c->
            if(quoted) {
                if(escaped)escaped=false
                else if(c=='\\')escaped=true
                else if(c=='"') {
                    quoted=false
                    var lookahead=index+1
                    while(lookahead<json.length && json[lookahead].isWhitespace())lookahead++
                    if(lookahead<json.length && json[lookahead]==':') {
                        val key=wire.decodeFromString<String>(json.substring(start,index+1))
                        require(stack.lastOrNull()?.add(key)==true) {"备份包含重复字段"}
                    }
                }
            } else when(c) {'"'->{quoted=true;start=index};'{'->stack.add(mutableSetOf());'['->stack.add(null);'}',']'->if(stack.isNotEmpty())stack.removeAt(stack.lastIndex)}
        }
    }
}
