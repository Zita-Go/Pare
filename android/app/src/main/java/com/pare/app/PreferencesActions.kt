package com.pare.app

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId

data class ProfileDraft(val background: String = "", val planningMinutes: Int = 30, val startingMinutes: Int = 5, val notes: String = "") {
    fun validationError(): String? = when {
        planningMinutes !in 5..480 -> "每次规划请填写 5–480 分钟"
        startingMinutes !in 1..15 -> "启动小步骤请填写 1–15 分钟"
        notes.length > 600 -> "其他偏好最多 600 字"
        background.length > 10000 -> "个人背景最多 10000 字"
        else -> null
    }
    companion object {
        fun from(entity: Entity?): ProfileDraft {
            val c=entity?.content(); val p=c?.get("preferences") as? JsonObject
            return ProfileDraft(c?.stringValue("background").orEmpty(),(p?.get("planningMinutes") as? JsonPrimitive)?.intOrNull ?: 30,
                (p?.get("startingMinutes") as? JsonPrimitive)?.intOrNull ?: 5,p?.stringValue("notes").orEmpty())
        }
    }
}
data class ProfileBaseline(val isNew: Boolean, val draft: ProfileDraft) {
    companion object { fun from(entity: Entity?) = ProfileBaseline(entity == null,ProfileDraft.from(entity)) }
}
data class ConfirmedPreferenceDraft(
    val id: String = uid(), val text: String = "", val ideaId: String? = null, val expiresAt: String? = null
) {
    fun validationError(): String? = when {
        text.trim().isEmpty() || text.length > 500 -> "偏好需填写 1–500 字"
        expiresAt != null && runCatching { Instant.parse(expiresAt) }.isFailure -> "请选择有效的到期时间"
        else -> null
    }
    companion object {
        fun from(entry: JsonObject) = ConfirmedPreferenceDraft(requireNotNull(entry.stringValue("id")),
            entry.stringValue("text").orEmpty(),entry.stringValue("ideaID"),entry.stringValue("expiresAt"))
    }
}
data class PreferenceBaseline(val profileExisted: Boolean, val fields: Map<String, JsonElement?>?) {
    companion object {
        private val keys=setOf("id","text","ideaID","source","expiresAt")
        fun from(profile: Entity?, id: String) = PreferenceBaseline(profile != null,
            PreferencesActions.entries(profile).firstOrNull { it.stringValue("id") == id }?.let { managedFields(it,keys) })
    }
}

object PreferencesActions {
    fun entries(profile: Entity?): List<JsonObject> = (profile?.content()?.get("confirmedPreferences") as? JsonArray).orEmpty().map { it.jsonObject }

    fun planningZone(state: SyncState): ZoneId {
        val profile=state.items["profile:profile"]?.takeUnless { it.deleted }
        val stored=profile?.content()?.stringValue("planningTimeZone")
        val fallback=state.items.values.filter { it.kind == "day_plan" && !it.deleted }.sortedBy { it.id }
            .firstNotNullOfOrNull { it.content()?.stringValue("time_zone") }
        return runCatching { ZoneId.of(stored ?: fallback ?: ZoneId.systemDefault().id) }.getOrDefault(ZoneId.systemDefault())
    }

    private fun content(profile: Entity?, zone: ZoneId): MutableMap<String,JsonElement> = profile?.content()?.toMutableMap() ?: mutableMapOf(
        "background" to JsonPrimitive(""),"planningTimeZone" to JsonPrimitive(zone.id),
        "preferences" to buildJsonObject { put("planningMinutes",30); put("startingMinutes",5); put("notes","") },
        "confirmedPreferences" to JsonArray(emptyList()))

    private fun save(state: SyncState, profile: Entity?, c: Map<String,JsonElement>): SyncState {
        val payload=JsonObject(profile?.payload.orEmpty() + ("content" to JsonObject(c)))
        require(payload != profile?.payload) { "没有需要保存的修改" }
        PayloadValidation.validate("profile","profile",payload)
        return StateMachine.edit(state,"profile","profile",payload)
    }

    fun saveProfile(state: SyncState, draft: ProfileDraft, expected: ProfileBaseline, zone: ZoneId = planningZone(state)): SyncState {
        draft.validationError()?.let { throw IllegalArgumentException(it) }
        val profile=state.items["profile:profile"]
        if(expected.isNew && profile!=null && draft!=expected.draft) {
            requireEditableEntity(profile)
            if(ProfileDraft.from(profile)==draft) return state
        }
        if (expected.isNew) requireNoExistingEntity(profile) else {
            requireEditableEntity(profile)
            require(ProfileBaseline.from(profile) == expected) { "规划偏好已更新，草稿已保留。请查看最新版本。" }
        }
        require(draft != expected.draft) { "没有需要保存的修改" }
        val c=content(profile,zone)
        c["background"]=JsonPrimitive(draft.background)
        val prefs=(c["preferences"] as? JsonObject).orEmpty().toMutableMap()
        prefs["planningMinutes"]=JsonPrimitive(draft.planningMinutes)
        prefs["startingMinutes"]=JsonPrimitive(draft.startingMinutes)
        prefs["notes"]=JsonPrimitive(draft.notes)
        c["preferences"]=JsonObject(prefs)
        return save(state,profile,c)
    }

    private fun checkPreference(profile: Entity?, id: String, expected: PreferenceBaseline) {
        if (expected.profileExisted) requireEditableEntity(profile)
        else if (profile != null) requireEditableEntity(profile) // concurrent additions are merged, never reset
        val current=PreferenceBaseline.from(profile,id)
        require(current.fields == expected.fields) { "这条偏好已更新或已忘记，草稿已保留。请查看最新版本。" }
    }

    fun saveConfirmed(state: SyncState, draft: ConfirmedPreferenceDraft, expected: PreferenceBaseline, now: Instant,
        zone: ZoneId = planningZone(state)): SyncState {
        draft.validationError()?.let { throw IllegalArgumentException(it) }
        val profile=state.items["profile:profile"]
        if(expected.fields==null && profile!=null) {
            requireEditableEntity(profile)
            val existing=entries(profile).firstOrNull {it.stringValue("id")==draft.id}
            if(existing!=null && ConfirmedPreferenceDraft.from(existing)==draft.copy(text=draft.text.trim()) && existing.stringValue("source")=="用户填写") return state
        }
        checkPreference(profile,draft.id,expected)
        draft.ideaId?.let { requireEditableEntity(state.items["idea:$it"]) }
        val list=entries(profile).toMutableList()
        val index=list.indexOfFirst { it.stringValue("id") == draft.id }
        val old=list.getOrNull(index)
        val entry: MutableMap<String,JsonElement> = old?.toMutableMap() ?: mutableMapOf("id" to JsonPrimitive(draft.id.lowercase()),"source" to JsonPrimitive("用户填写"))
        entry["text"]=JsonPrimitive(draft.text.trim())
        if (draft.ideaId == null) entry.remove("ideaID") else entry["ideaID"]=JsonPrimitive(draft.ideaId)
        if (draft.expiresAt == null) entry.remove("expiresAt") else entry["expiresAt"]=JsonPrimitive(draft.expiresAt)
        require(old == null || entry != old) { "没有需要保存的修改" }
        entry["updatedAt"]=JsonPrimitive(now.toString())
        if (index < 0) list += JsonObject(entry) else list[index]=JsonObject(entry)
        val c=content(profile,zone); c["confirmedPreferences"]=JsonArray(list)
        return save(state,profile,c)
    }

    fun forget(state: SyncState, id: String, expected: PreferenceBaseline): SyncState {
        require(expected.fields != null) { "这条偏好已忘记或不可用" }
        val profile=state.items["profile:profile"]
        checkPreference(profile,id,expected)
        val c=requireNotNull(profile?.content()).toMutableMap()
        c["confirmedPreferences"]=JsonArray(entries(profile).filterNot { it.stringValue("id") == id })
        return save(state,profile,c)
    }
}
