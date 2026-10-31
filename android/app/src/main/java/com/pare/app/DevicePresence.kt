package com.pare.app

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val DEVICE_PRESENCE_MAX_SECONDS = 31_536_000L
internal const val DEVICE_PRESENCE_MAX_SEQUENCE = 9_007_199_254_740_991L

internal fun presenceUUID(value: String) = require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) { "跨端运行状态标识无效" }

/** Advisory metadata only: task/run IDs and timing, never titles, notes, credentials or AI content. */
@Serializable
data class DevicePresenceRun(
    @SerialName("run_id") val runId: String,
    @SerialName("task_id") val taskId: String,
    val kind: String,
    val state: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("elapsed_seconds") val elapsedSeconds: Long,
    @SerialName("target_seconds") val targetSeconds: Int? = null
) {
    fun validate() {
        presenceUUID(runId); presenceUUID(taskId)
        require(kind in setOf("personal", "waiting") && state in setOf("running", "paused")) { "跨端运行状态类型无效" }
        Instant.parse(startedAt)
        require(elapsedSeconds in 0..DEVICE_PRESENCE_MAX_SECONDS && (targetSeconds == null || targetSeconds in 1..DEVICE_PRESENCE_MAX_SECONDS.toInt())) { "跨端运行时间无效" }
    }
}

@Serializable
data class DevicePresenceRequest(
    @SerialName("p_device_id") val deviceId: String,
    @SerialName("p_sequence") val sequence: Long,
    @SerialName("p_label") val label: String = "Android",
    @SerialName("p_runs") val runs: List<DevicePresenceRun> = emptyList()
) {
    fun validate() {
        presenceUUID(deviceId)
        require(sequence in 1..DEVICE_PRESENCE_MAX_SEQUENCE && label == "Android" && runs.size <= 32 && runs.map { it.runId }.distinct().size == runs.size && runs.map { it.taskId }.distinct().size == runs.size && runs.count { it.kind == "personal" } <= 1) { "跨端运行请求无效" }
        runs.forEach(DevicePresenceRun::validate)
    }
}

@Serializable
data class DevicePresenceDevice(
    @SerialName("device_id") val deviceId: String,
    val sequence: Long,
    val label: String,
    @SerialName("last_seen") val lastSeen: String,
    @SerialName("expires_at") val expiresAt: String,
    val runs: List<DevicePresenceRun>
) {
    fun validate(serverNow: Instant) {
        presenceUUID(deviceId)
        require(sequence in 1..DEVICE_PRESENCE_MAX_SEQUENCE && label in setOf("Mac", "Android") && runs.size <= 32 && runs.map { it.runId }.distinct().size == runs.size && runs.map { it.taskId }.distinct().size == runs.size && runs.count { it.kind == "personal" } <= 1) { "设备状态无效" }
        val seen = Instant.parse(lastSeen); val expiry = Instant.parse(expiresAt)
        require(!seen.isAfter(serverNow) && !seen.isBefore(serverNow.minusSeconds(86_400)) && expiry.isAfter(seen) && Duration.between(seen, expiry).toMillis() <= 90_000L) { "设备状态有效期无效" }
        runs.forEach(DevicePresenceRun::validate)
    }
}

@Serializable
data class DevicePresenceReply(
    val version: Int,
    @SerialName("server_now") val serverNow: String,
    @SerialName("accepted_sequence") val acceptedSequence: Long,
    val devices: List<DevicePresenceDevice>
) {
    fun validate(request: DevicePresenceRequest) {
        request.validate()
        require(version == 1 && acceptedSequence >= request.sequence && acceptedSequence <= DEVICE_PRESENCE_MAX_SEQUENCE && devices.size in 1..32 && devices.map { it.deviceId }.distinct().size == devices.size) { "跨端运行响应无效" }
        val now = Instant.parse(serverNow)
        devices.forEach { it.validate(now) }
        val own = requireNotNull(devices.firstOrNull { it.deviceId == request.deviceId }) { "跨端运行回执缺少本机状态" }
        require(own.sequence == acceptedSequence) { "本机运行回执不一致" }
        if (acceptedSequence == request.sequence) require(own.label == request.label && own.runs == request.runs) { "本机运行回执与请求不符" }
    }
}

class DevicePresenceUnavailableException : Exception("跨端运行状态服务尚未启用")

data class DevicePresencePeer(val deviceId: String, val label: String, val lastSeen: String, val expiresAt: String,
    val fresh: Boolean, val runs: List<DevicePresenceRun>)
data class DevicePresencePeerRun(val deviceId: String, val label: String, val run: DevicePresenceRun)
data class DevicePresenceState(
    val owner: String? = null,
    val device: String? = null,
    val peers: List<DevicePresencePeer> = emptyList(),
    val status: String = "开启账号同步后，可查看其他设备最近报告的运行状态",
    val lastConfirmedAt: String? = null,
    val refreshing: Boolean = false
) {
    fun freshPeerFocus(): List<DevicePresencePeerRun> = peers.filter { it.fresh }.flatMap { peer ->
        peer.runs.filter { it.kind == "personal" && it.state == "running" }.map { DevicePresencePeerRun(peer.deviceId, peer.label, it) }
    }
}

/** A server clock anchored to elapsed time; wall-clock changes cannot extend freshness.
 * Anchor at request start, conservatively including the entire network round trip. */
data class DevicePresenceObservation(val owner: String, val device: String, val reply: DevicePresenceReply, val anchor: ExecutionMoment) {
    fun project(now: ExecutionMoment): List<DevicePresencePeer> {
        val usable = now.bootId == anchor.bootId && now.elapsedMillis >= anchor.elapsedMillis
        val server = Instant.parse(reply.serverNow)
        val estimatedNow = if (usable) runCatching { server.plusMillis(now.elapsedMillis - anchor.elapsedMillis) }.getOrNull() else null
        return reply.devices.filter { it.deviceId != device }.map { item ->
            val fresh = estimatedNow != null && estimatedNow.isBefore(Instant.parse(item.expiresAt))
            DevicePresencePeer(item.deviceId, item.label, item.lastSeen, item.expiresAt, fresh, item.runs)
        }.sortedWith(compareByDescending<DevicePresencePeer> { it.fresh }.thenBy { it.label }.thenBy { it.deviceId })
    }
}

object DevicePresenceProjection {
    fun runs(execution: ExecutionSnapshot, now: ExecutionMoment): List<DevicePresenceRun> {
        fun project(run: ExecutionRun, kind: String) = DevicePresenceRun(
            run.id, run.taskId, kind, if (run.paused) "paused" else "running", Instant.ofEpochMilli(run.startedAtMillis).toString(),
            (run.elapsedMillis(now) / 1000L).coerceIn(0, DEVICE_PRESENCE_MAX_SECONDS), run.targetMinutes?.let { it * 60 }
        )
        return (listOfNotNull(execution.focus?.takeIf { it.pendingFinish == null }?.let { project(it, "personal") }) +
            execution.external.filter { it.pendingFinish == null }.sortedBy { it.id }.map { project(it, "waiting") }).take(32)
    }
}
