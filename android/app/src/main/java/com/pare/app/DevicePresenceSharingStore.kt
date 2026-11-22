package com.pare.app

import android.content.Context
import android.content.SharedPreferences

/** Separate opt-in from content sync. Preferences are device-local and excluded from app backup. */
class DevicePresenceSharingStore internal constructor(private val preferences: SharedPreferences, private val scope: String) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("device-presence-sharing", Context.MODE_PRIVATE),
        context.applicationContext.packageName
    )
    companion object {
        private val lock = Any()
        // SharedPreferences updates memory even when commit fails. The denial must survive
        // constructing another store in this process and cannot rely on a rollback write.
        private val blocked = mutableSetOf<Pair<String, String>>()
    }
    private fun key(owner: String, device: String): String {
        require(owner.isNotBlank()) { "请先登录账号，再选择共享运行状态" }
        presenceUUID(device)
        return "v1:$owner:$device"
    }
    fun enabled(owner: String?, device: String): Boolean = synchronized(lock) {
        if (owner == null) return@synchronized false
        val key = key(owner, device); val row = scope to key
        if (row in blocked) false else try { preferences.getBoolean(key, false) }
        catch (_: Exception) { blocked += row; false }
    }
    fun set(owner: String, device: String, enabled: Boolean) = synchronized(lock) {
        val key = key(owner, device); val row = scope to key
        try {
            check(preferences.edit().putBoolean(key, enabled).commit())
            // Only a subsequent acknowledged write clears a failed consent write.
            blocked -= row
        } catch (error: Exception) {
            blocked += row
            runCatching { preferences.edit().putBoolean(key, false).apply() }
            throw IllegalStateException("共享选择尚未保存，本机已停止共享；请重试", error)
        }
    }
}
