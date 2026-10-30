package com.pare.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*
import java.util.UUID

val wire = Json { ignoreUnknownKeys = true; encodeDefaults = true }
fun uid() = UUID.randomUUID().toString()
@Serializable data class Entity(val kind: String, val id: String, val payload: JsonObject? = null, val revision: Long = 0, val deleted: Boolean = false, val base: JsonObject? = null, val dirty: Boolean = false, val conflict: Remote? = null, val group: String = "", val conflictGroup: String = "", val conflictChoice: Boolean? = null) { val key get() = "$kind:$id" }
@Serializable data class Mutation(val kind: String, val id: String, @SerialName("base_revision") val baseRevision: Long, @SerialName("payload_version") val version: Int = 1, val deleted: Boolean = false, val payload: JsonObject? = null) { val key get() = "$kind:$id" }
@Serializable data class Remote(val kind: String, val id: String, val revision: Long, @SerialName("payload_version") val version: Int = 1, val payload: JsonObject? = null, @SerialName("deleted_at") val deletedAt: String? = null, @SerialName("last_device_id") val device: String, val sequence: Long) { val key get() = "$kind:$id" }
@Serializable data class Push(@SerialName("p_operation_id") val operation: String, @SerialName("p_device_id") val device: String, @SerialName("p_mutations") val mutations: List<Mutation>)
@Serializable data class Conflict(val kind: String, val id: String, val current: Remote? = null)
@Serializable data class Reply(@SerialName("protocol_version") val version: Int, val status: String, val sequence: Long, val entities: List<Remote> = emptyList(), val conflicts: List<Conflict> = emptyList())
@Serializable data class Commit(val sequence: Long, @SerialName("operation_id") val operation: String, @SerialName("device_id") val device: String, val entities: List<Remote>)
@Serializable data class Page(@SerialName("protocol_version") val version: Int, val cursor: Long, @SerialName("has_more") val more: Boolean, val commits: List<Commit>)
@Serializable data class SyncState(val owner: String? = null, val device: String = uid(), val cursor: Long = 0, val items: Map<String, Entity> = emptyMap(), val pending: Push? = null, val enabled: Boolean = false)

object StateMachine {
    private val kinds = setOf("inbox", "task", "idea", "goal", "profile", "day_plan")
    private fun validKey(kind:String,id:String) {
        require(kind in kinds && id.length in 1..160 && id.matches(Regex("[A-Za-z0-9_:@+./-]+"))) {"无效的同步标识"}
    }
    private fun validate(r:Remote) {
        validKey(r.kind,r.id)
        require(r.version==1 && r.revision>0 && r.sequence>0 && (r.deletedAt!=null)==(r.payload==null)) {"不支持的云端数据"}
        r.payload?.let {PayloadValidation.validate(r.kind,r.id,it)}
    }
    fun edit(s:SyncState, kind:String, id:String, payload:JsonObject?, deleted:Boolean=false):SyncState {
        validKey(kind,id)
        require(deleted==(payload==null))
        val key="$kind:$id"
        val old=s.items[key] ?: Entity(kind,id)
        val next=old.copy(payload=payload,deleted=deleted,dirty=true,group=old.group.ifEmpty {uid()})
        val items=s.items+(key to next)
        return s.copy(items=if(old.conflictGroup.isEmpty()) items else items.mapValues {(_,entity) ->
            if(entity.conflictGroup==old.conflictGroup) entity.copy(conflictChoice=null) else entity
        })
    }
    /** All entities edited by one local action must travel in the same RPC. */
    fun group(s:SyncState,keys:Set<String>):SyncState {
        val inherited=s.items.values.filter {it.key in keys}.map {it.group}.toSet()
        val group=uid()
        return s.copy(items=s.items.mapValues { (_,v) -> if(v.dirty && (v.key in keys || v.group in inherited)) v.copy(group=group) else v })
    }
    fun prepare(s:SyncState):SyncState {
        if(s.pending!=null) return s
        val dirty=s.items.values.filter {it.dirty}.groupBy {it.group.ifEmpty {it.key}}
        val selected=mutableListOf<Entity>()
        for(group in dirty.values) {
            if(group.any {it.conflict!=null}) continue
            require(group.size<=100) {"单次操作超过同步上限"}
            if(selected.size+group.size>100) break
            val candidate=(selected+group).map {Mutation(it.kind,it.id,it.revision,deleted=it.deleted,payload=it.payload)}
            if(wire.encodeToString(candidate).toByteArray().size>1_048_576) {
                require(selected.isNotEmpty()) {"单次操作内容超过同步上限"}
                break
            }
            selected+=group
        }
        if(selected.isEmpty()) return s
        val mutations=selected.map {Mutation(it.kind,it.id,it.revision,deleted=it.deleted,payload=it.payload)}
        require(wire.encodeToString(mutations).toByteArray().size<=1_048_576) {"同步内容过大，请缩短内容后重试"}
        return s.copy(pending=Push(uid(),s.device,mutations))
    }
    fun accept(s:SyncState,reply:Reply):SyncState {
        val request=requireNotNull(s.pending)
        require(reply.version==1 && reply.sequence>=0)
        val expected=request.mutations.associateBy {it.key}
        if(reply.status=="applied") {
            require(reply.sequence>0 && reply.conflicts.isEmpty() && reply.entities.size==expected.size && reply.entities.map {it.key}.toSet()==expected.keys)
            reply.entities.forEach {r ->
                validate(r)
                val m=expected.getValue(r.key)
                require(r.sequence==reply.sequence && r.revision==m.baseRevision+1 && r.device==request.device && r.payload==m.payload && (r.deletedAt!=null)==m.deleted) {"云端回执与请求不符"}
            }
            var items=s.items
            for(r in reply.entities) {
                val local=items.getValue(r.key)
                val sent=expected.getValue(r.key)
                val dirty=local.payload!=sent.payload || local.deleted!=sent.deleted
                if(local.revision>r.revision) continue
                val newerConflict=local.conflict?.takeIf {it.revision>r.revision}
                items=items+(r.key to local.copy(revision=r.revision,base=r.payload,dirty=dirty || newerConflict!=null,conflict=newerConflict,group=if(dirty || newerConflict!=null) local.group else "",conflictGroup=if(newerConflict!=null) local.conflictGroup else "",conflictChoice=null))
            }
            return s.copy(items=items,pending=null)
        }
        require(reply.status=="conflict" && reply.entities.isEmpty() && reply.conflicts.isNotEmpty() && reply.conflicts.map {"${it.kind}:${it.id}"}.toSet().size==reply.conflicts.size)
        val remotes=reply.conflicts.map {c ->
            val key="${c.kind}:${c.id}"
            require(key in expected)
            val remote=requireNotNull(c.current) {"云端版本缺失，请保留本机数据并重试"}
            validate(remote);require(remote.key==key && remote.sequence<=reply.sequence)
            remote
        }
        return hold(s.copy(pending=null),remotes,expected.keys)
    }
    /** Stage the complete overlapping local/remote transaction, including absent siblings. */
    private fun hold(s:SyncState,remotes:List<Remote>,extra:Set<String> = emptySet()):SyncState {
        val keys=(remotes.map {it.key}+extra).toMutableSet()
        do {
            val before=keys.size
            val groups=s.items.values.filter {it.key in keys}.flatMap {listOf(it.group,it.conflictGroup)}.filter {it.isNotEmpty()}.toSet()
            s.items.values.filter {it.group in groups || it.conflictGroup in groups}.forEach {keys.add(it.key)}
        } while(keys.size!=before)
        val candidates=remotes.associateBy {it.key}
        val group=uid()
        val items=s.items.toMutableMap()
        val changed=keys.any {key ->
            val old=items[key]
            val incoming=candidates[key]
            old?.conflict==null || (incoming!=null && incoming.revision>(old.conflict?.revision ?: old.revision))
        }
        for(key in keys) {
            val candidate=candidates[key]
            val existing=items[key]
            val old=existing ?: Entity(candidate!!.kind,candidate.id,deleted=true)
            val known=old.conflict ?: Remote(old.kind,old.id,old.revision,payload=old.base,deletedAt=if(old.base==null) "1970-01-01T00:00:00Z" else null,device=s.device,sequence=maxOf(1,s.cursor))
            // A persisted receipt can lag the pull cursor. It can never replace a newer snapshot.
            val remote=if(candidate!=null && candidate.revision>=maxOf(known.revision,old.revision)) candidate else known
            items[key]=old.copy(conflict=remote,group=group,conflictGroup=group,conflictChoice=if(changed) null else old.conflictChoice)
        }
        return s.copy(items=items)
    }

    fun pull(s:SyncState,page:Page):SyncState {
        require(page.version==1 && page.cursor>=s.cursor && page.commits.size<=100)
        var previous=s.cursor
        val operations=mutableSetOf<String>()
        page.commits.forEach {c ->
            require(c.sequence==previous+1 && operations.add(c.operation) && c.entities.size in 1..100 && c.entities.map {it.key}.toSet().size==c.entities.size)
            c.entities.forEach {validate(it);require(it.sequence==c.sequence && it.device==c.device)}
            previous=c.sequence
        }
        require(page.cursor==previous && (page.commits.isNotEmpty() || !page.more)) {"云端游标不连续"}
        var result=s
        for(c in page.commits) {
            if(c.operation==result.pending?.operation) {
                require(c.device==result.device)
                result=accept(result,Reply(1,"applied",c.sequence,c.entities))
            }
            val fresh=c.entities.filter {r ->r.revision>maxOf(result.items[r.key]?.revision ?: 0,result.items[r.key]?.conflict?.revision ?: 0)}
            val hasConflict=c.entities.any {result.items[it.key]?.conflict!=null} || fresh.any {r ->
                val local=result.items[r.key]
                local?.conflict!=null || (local?.dirty==true && (local.payload!=r.payload || local.deleted!=(r.deletedAt!=null)))
            }
            if(hasConflict) result=hold(result,c.entities)
            else {
                var items=result.items
                for(r in fresh) items=items+(r.key to Entity(r.kind,r.id,r.payload,r.revision,r.deletedAt!=null,r.payload))
                result=result.copy(items=items)
            }
        }

        return result.copy(cursor=page.cursor)
    }
    fun resolve(s:SyncState,key:String,keepLocal:Boolean):SyncState {
        require(s.pending==null) {"先同步待确认的请求再选择版本"}
        val local=s.items.getValue(key)
        val r=requireNotNull(local.conflict)
        require(r.revision>=local.revision) {"冲突版本已过期，请重新同步"}
        val selected=s.items+(key to local.copy(conflictChoice=keepLocal))
        val members=selected.values.filter {it.conflict!=null && (it.key==key || (local.conflictGroup.isNotEmpty() && it.conflictGroup==local.conflictGroup))}
        if(members.any {it.conflictChoice==null}) return s.copy(items=selected)
        val items=selected.toMutableMap()
        val uploadGroup=uid()
        for(member in members) {
            val remote=requireNotNull(member.conflict)
            require(remote.revision>=member.revision)
            items[member.key]=if(member.conflictChoice==true) member.copy(revision=remote.revision,base=remote.payload,conflict=null,dirty=true,group=uploadGroup,conflictGroup="",conflictChoice=null)
                else Entity(remote.kind,remote.id,remote.payload,remote.revision,remote.deletedAt!=null,remote.payload)
        }
        return s.copy(items=items)
    }

    fun bind(s:SyncState,owner:String):SyncState {
        require(owner.isNotBlank() && (s.owner==null || s.owner==owner)) {"本机数据绑定另一账号，请使用原账号登录"}
        return s.copy(owner=owner)
    }
}
