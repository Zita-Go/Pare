package com.pare.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** Blocking HTTPS has connect/read timeouts; cancellation is checked at every engine boundary. */
class HttpSyncRemote(private val api:SupabaseApi):SyncRemote {
    private suspend fun <T> io(block:()->T):T=runInterruptible(Dispatchers.IO,block)
    override suspend fun login(email:String,password:String,signup:Boolean)=io {api.login(email,password,signup)}
    override suspend fun refresh(session:Session)=io {api.refresh(session)}
    override suspend fun pull(session:Session,cursor:Long)=io {api.pull(session,cursor)}
    override suspend fun push(session:Session,request:Push)=io {api.push(session,request)}
    override suspend fun logout(session:Session)=io {api.logout(session)}
    override suspend fun presence(session:Session,request:DevicePresenceRequest)=io {api.presence(session,request)}
}
