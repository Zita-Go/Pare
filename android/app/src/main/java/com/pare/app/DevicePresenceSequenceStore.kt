package com.pare.app

interface DevicePresenceSequenceStore {
    /** Must commit durably before returning; a lost response never permits sequence reuse. */
    fun next(owner: String, device: String): Long
    fun advance(owner: String, device: String, accepted: Long)
}
