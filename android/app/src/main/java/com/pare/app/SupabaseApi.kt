package com.pare.app
import kotlinx.serialization.json.*
/** Bound total response time as well as individual socket reads, including slow trickles. */
internal fun readSyncResponse(input:java.io.InputStream,deadlineNanos:Long,nanoTime:()->Long=System::nanoTime):ByteArray {
    val output=java.io.ByteArrayOutputStream()
    val buffer=ByteArray(8192)
    fun checkActive() {
        if(Thread.currentThread().isInterrupted || nanoTime()>=deadlineNanos) throw java.io.InterruptedIOException("同步请求已停止或超时")
    }
    while(true) {
        checkActive()
        val count=input.read(buffer)
        checkActive()
        if(count<0) break
        require(output.size()+count<=5_242_880) {"云端响应过大"}
        output.write(buffer,0,count)
    }
    return output.toByteArray()
}
fun parseSession(json:JsonObject, now:Long):Session? {
    val access=json["access_token"]?.jsonPrimitive?.contentOrNull ?: return null
    val refresh=json["refresh_token"]?.jsonPrimitive?.contentOrNull
    val user=json["user"] as? JsonObject
    val owner=user?.get("id")?.jsonPrimitive?.contentOrNull
    require(access.isNotBlank() && !refresh.isNullOrBlank() && !owner.isNullOrBlank()) {"登录响应不完整"}
    val expiry=json["expires_at"]?.jsonPrimitive?.longOrNull ?: (now+(json["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600))
    require(expiry>now)
    return Session(owner,user["email"]?.jsonPrimitive?.contentOrNull ?: "",access,refresh,expiry)
}

class ApiFailure(val code:Int):Exception(when(code) {401 -> "登录已失效，请重新登录"; 400,422 -> "请检查邮箱、密码或邮件验证状态"; 429 -> "请求较多，请稍后重试"; else -> "云端暂不可用（$code）"})
class SupabaseApi(private val baseUrl:String,private val publicKey:String) {
    fun post(path:String, body:JsonObject, token:String?=null):JsonObject {
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60)
        val base=baseUrl.trimEnd('/')
        require(base.startsWith("https://") && publicKey.isNotBlank()) {"此构建尚未配置同步服务"}
        val conn=java.net.URL("$base/$path").openConnection() as javax.net.ssl.HttpsURLConnection
        conn.requestMethod="POST";conn.connectTimeout=15000;conn.readTimeout=20000;conn.doOutput=true;conn.instanceFollowRedirects=false
        conn.setRequestProperty("Content-Type","application/json")
        conn.setRequestProperty("apikey",publicKey)
        token?.let { conn.setRequestProperty("Authorization","Bearer $it") }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status=conn.responseCode
            if(status !in 200..299) throw ApiFailure(status)
            val bytes=conn.inputStream.use {readSyncResponse(it,deadline)}
            require(bytes.size<=5_242_880) {"云端响应过大"}
            if(bytes.isEmpty()) return JsonObject(emptyMap())
            return wire.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        } finally {conn.disconnect()}
    }
    fun login(email:String,password:String,signup:Boolean):Session? = parseSession(post(if(signup) "auth/v1/signup" else "auth/v1/token?grant_type=password",buildJsonObject {put("email",email);put("password",password)}),System.currentTimeMillis()/1000)
    fun refresh(s:Session):Session = parseSession(post("auth/v1/token?grant_type=refresh_token",buildJsonObject {put("refresh_token",s.refreshToken)}),System.currentTimeMillis()/1000) ?: error("请重新登录")
    fun pull(s:Session,cursor:Long):Page = wire.decodeFromJsonElement(post("rest/v1/rpc/pare_sync_pull",buildJsonObject {put("p_after",cursor);put("p_limit",1)},s.accessToken))
    fun push(s:Session,request:Push):Reply = wire.decodeFromJsonElement(post("rest/v1/rpc/pare_sync_apply",wire.encodeToJsonElement(request).jsonObject,s.accessToken))
    fun presence(s:Session,request:DevicePresenceRequest):DevicePresenceReply {
        request.validate()
        return wire.decodeFromJsonElement<DevicePresenceReply>(post("rest/v1/rpc/pare_presence_exchange_v2",wire.encodeToJsonElement(request).jsonObject,s.accessToken)).also {it.validate(request)}
    }
    fun logout(s:Session) {post("auth/v1/logout?scope=local",JsonObject(emptyMap()),s.accessToken)}
}
