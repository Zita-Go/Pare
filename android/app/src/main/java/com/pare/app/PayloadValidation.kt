package com.pare.app

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Validate all consumed v1 fields before saving either content or the stream cursor. */
object PayloadValidation {
    private fun obj(e:JsonElement?)=requireNotNull(e as? JsonObject)
    private fun arr(e:JsonElement?)=requireNotNull(e as? JsonArray)
    private fun str(e:JsonElement?):String {val p=requireNotNull(e as? JsonPrimitive);require(p.isString);return p.content}
    private fun uuid(e:JsonElement?):String {val value=str(e);require(UUID.fromString(value).toString()==value);return value}
    private fun integer(e:JsonElement?):Int {val p=requireNotNull(e as? JsonPrimitive);require(!p.isString);return requireNotNull(p.intOrNull)}
    private fun bool(e:JsonElement?) {val p=requireNotNull(e as? JsonPrimitive);require(!p.isString && p.booleanOrNull!=null)}
    private fun date(e:JsonElement?) {Instant.parse(str(e))}
    private fun optional(c:JsonObject,key:String,check:(JsonElement)->Unit) {c[key]?.takeUnless {it==JsonNull}?.let(check)}
    private fun strings(c:JsonObject,vararg keys:String) {keys.forEach {optional(c,it){v->str(v)}}}
    private fun dates(c:JsonObject,vararg keys:String) {keys.forEach {optional(c,it,::date)}}
    private fun ints(c:JsonObject,vararg keys:String) {keys.forEach {optional(c,it){v->integer(v)}}}
    private fun choice(e:JsonElement?,values:Set<String>) {require(str(e) in values)}
    fun validate(kind:String,id:String,payload:JsonObject) {
        try {checkContent(kind,id,payload)} catch(e:Exception) {throw IllegalArgumentException("云端内容格式不受支持，尚未应用或推进游标",e)}
    }
    private fun checkContent(kind:String,id:String,payload:JsonObject) {
        val c=obj(payload["content"])
        if(kind in setOf("inbox","task","idea","goal")) {require(uuid(c["id"])==id);date(c["createdAt"])}
        when(kind) {
            "inbox" -> {str(c["text"]);dates(c,"suggestedRemindAt")}
            "task" -> {
                str(c["text"]);require(integer(c["quadrant"]) in 0..3);bool(c["completed"])
                choice(payload["lifecycle"],setOf("active","archived"))
                strings(c,"note","nextAction","definitionOfDone")
                dates(c,"completedAt","remindAt","lastProgressAt","dueAt")
                ints(c,"estimatedMinutes","actualMinutes")
                optional(c,"completedSteps") {value ->
                    val ids=mutableSetOf<String>()
                    arr(value).forEach {record ->
                        val entry=obj(record);require(ids.add(uuid(entry["id"])));require(str(entry["text"]).isNotBlank())
                        date(entry["completedAt"]);strings(entry,"definitionOfDone")
                    }
                }
                optional(c,"recurrence"){choice(it,setOf("daily","weekly","monthly"))}
                optional(c,"implementationPlan") {value ->val p=obj(value);choice(p["kind"],setOf("time","returnToComputer","openApplication"));str(p["action"]);ints(p,"hour","minute");strings(p,"applicationBundleID","applicationName")}
                listOf("progressSessions","parallelRunSessions","blockerEntries").forEach {key ->optional(c,key) {array -> arr(array).forEach {value ->
                    val entry=obj(value);uuid(entry["id"])
                    if(key=="blockerEntries") {date(entry["at"]);choice(entry["reason"],setOf("unclear","tooLarge","lowEnergy","resistance"))}
                    else {date(entry["startedAt"]);date(entry["endedAt"]);integer(entry[if(key=="progressSessions") "minutes" else "elapsedMinutes"]);ints(entry,"targetMinutes");if(key=="parallelRunSessions")choice(entry["reason"],setOf("paused","completed"))}
                }}}
            }
            "idea" -> {
                str(c["text"]);date(c["updatedAt"]);strings(c,"notes","nextQuestion");dates(c,"reviewAt","nextQuestionUpdatedAt")
                optional(c,"stage"){choice(it,setOf("seed","exploring","building","parked","archived"))}
                optional(c,"linkedTaskIDs"){arr(it).forEach {v->uuid(v)}}
                optional(c,"entries"){arr(it).forEach {v->val e=obj(v);uuid(e["id"]);str(e["text"]);date(e["createdAt"]);choice(e["kind"],setOf("note","finding","decision"));strings(e,"author")}}
            }
            "goal" -> {str(c["title"]);str(c["motivation"]);date(c["updatedAt"]);dates(c,"targetDate");arr(c["taskIDs"]).forEach {uuid(it)};choice(c["status"],setOf("active","paused","achieved"))}
            "profile" -> {
                require(id=="profile");str(c["background"]);ZoneId.of(str(c["planningTimeZone"]))
                val p=obj(c["preferences"]);integer(p["planningMinutes"]);integer(p["startingMinutes"]);str(p["notes"])
                arr(c["confirmedPreferences"]).forEach {v->val e=obj(v);uuid(e["id"]);str(e["text"]);str(e["source"]);date(e["updatedAt"]);dates(e,"expiresAt");optional(e,"ideaID"){uuid(it)}}
            }
            "day_plan" -> {
                val day=str(c["day_key"]);val zone=str(c["time_zone"])
                require(LocalDate.parse(day).toString()==day);ZoneId.of(zone);require(id=="$day@$zone")
                val ids=arr(c["ordered_task_ids"]).map {uuid(it)};require(ids.distinct().size==ids.size)
                optional(c,"daily_win_task_id"){require(uuid(it) in ids)}
                val planIds=mutableSetOf<String>()
                optional(c,"task_plans"){arr(it).forEach {v->
                    val p=obj(v);require(planIds.add(uuid(p["task_id"])));dates(p,"scheduled_at")
                    optional(p,"scheduled_minutes"){n->require(integer(n)>0)}
                    optional(p,"schedule_reminder_lead_minutes"){n->require(integer(n)>=0)}
                    optional(p,"allocation"){a->val allocation=obj(a);uuid(allocation["id"]);choice(allocation["kind"],setOf("personal","check"));optional(allocation,"planned_minutes"){n->require(integer(n)>0)}}
                }}
            }
            else -> error("Unknown kind")
        }
    }
}
