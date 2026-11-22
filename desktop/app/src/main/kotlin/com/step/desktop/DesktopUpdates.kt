package com.step.desktop

import com.pare.app.wire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest

data class DesktopUpdateCandidate(val version:String,val notes:String,val url:String,val size:Long,val sha256:String)
data class DesktopUpdateState(val status:String="尚未检查更新",val busy:Boolean=false,val candidate:DesktopUpdateCandidate?=null,val downloaded:Boolean=false)
@Serializable internal data class DesktopUpdateCheckLedger(val nextCheckAt:Long=0,val failures:Int=0) {
    fun completed(now:Long,success:Boolean):DesktopUpdateCheckLedger {
        val nextFailures=if(success)0 else (failures+1).coerceAtMost(8)
        val delay=if(success)86_400_000L else (900_000L shl (nextFailures-1)).coerceAtMost(86_400_000L)
        return DesktopUpdateCheckLedger(now+delay,nextFailures)
    }
}

/** Explicit user actions only. Trust release metadata from this repository and verify every installer. */
internal class DesktopUpdates(private val home:Path,private val currentVersion:String=CURRENT_VERSION,
    private val connect:(String,Boolean)->HttpURLConnection={url,metadata->connection(url,metadata)}) {
    private val mutable=MutableStateFlow(DesktopUpdateState())
    val state:StateFlow<DesktopUpdateState> = mutable
    private var downloaded:Path?=null
    private val checksFile=DesktopAtomicFile(home.resolve("update-checks.json"),16*1024)
    private fun checks()=checksFile.read()?.let {wire.decodeFromString<DesktopUpdateCheckLedger>(it.toString(Charsets.UTF_8)).also {v->require(v.nextCheckAt>=0 && v.failures in 0..8)}} ?: DesktopUpdateCheckLedger()
    fun checkIfDue() {if(System.currentTimeMillis()>=checks().nextCheckAt)check()}
    fun check() {
        val previous=checks()
        // Commit the quiet interval before networking, including a killed or interrupted request.
        checksFile.write(wire.encodeToString(previous.completed(System.currentTimeMillis(),false)).toByteArray())
        mutable.value=mutable.value.copy(status="正在检查官方发布",busy=true)
        try {
            val bytes=connect("https://api.github.com/repos/Zita-Go/Pare/releases?per_page=30",true).let {c->try {c.inputStream.use {it.readNBytes(2_000_001)}}finally {c.disconnect()}}
            require(bytes.size<=2_000_000) {"更新目录过大"}
            val candidate=select(bytes.toString(Charsets.UTF_8),currentVersion)
            downloaded=null
            checksFile.write(wire.encodeToString(previous.completed(System.currentTimeMillis(),true)).toByteArray())
            mutable.value=DesktopUpdateState(if(candidate==null)"没有发现更高版本的已发布 Windows 安装包"else"发现 ${candidate.version}；下载后将校验 SHA-256，安装需要再次确认",candidate=candidate)
        } catch(e:Exception) {mutable.value=mutable.value.copy(status="检查更新失败，请稍后重试",busy=false);throw e}
    }
    fun download() {
        val candidate=requireNotNull(state.value.candidate) {"请先检查更新"}
        require(officialAsset(candidate.url) && candidate.sha256.matches(Regex("[a-fA-F0-9]{64}")) && candidate.size in 1..MAX_INSTALLER)
        mutable.value=state.value.copy(status="正在下载并校验安装包",busy=true,downloaded=false)
        var temporary:Path?=null
        try {
            Files.createDirectories(home.resolve("updates"))
            val partial=Files.createTempFile(home.resolve("updates"),"step-download-",".tmp")
            temporary=partial
            val c=connect(candidate.url,false)
            try {
                val deadline=System.nanoTime()+180_000_000_000L
                c.inputStream.use {input->Files.newOutputStream(partial).use {output->
                    val buffer=ByteArray(64*1024);var total=0L
                    while(true) {
                        check(!Thread.currentThread().isInterrupted && System.nanoTime()<deadline) {"下载已停止或超时"}
                        val count=input.read(buffer);if(count<0)break
                        total+=count;require(total<=candidate.size && total<=MAX_INSTALLER) {"安装包超过预期大小"}
                        output.write(buffer,0,count)
                    }
                }}
            } finally {c.disconnect()}
            verify(partial,candidate)
            val destination=home.resolve("updates/Step-${candidate.version}.exe")
            Files.move(partial,destination,ATOMIC_MOVE,REPLACE_EXISTING);downloaded=destination
            mutable.value=state.value.copy(status="安装包校验通过；点击安装后会退出 Step 并打开安装向导",busy=false,downloaded=true)
        } catch(e:Exception) {mutable.value=state.value.copy(status="下载或校验失败，没有启动安装程序",busy=false,downloaded=false);throw e}
        finally {temporary?.let {Files.deleteIfExists(it)}}
    }
    fun installer():Path {
        val file=requireNotNull(downloaded) {"请先下载并校验更新"}
        verify(file,requireNotNull(state.value.candidate));return file
    }
    companion object {
        private const val MAX_INSTALLER=700L*1024*1024
        private fun version(value:String)=value.split('.').map {it.toIntOrNull() ?: -1}.takeIf {it.size==3 && it.all {n->n>=0}}
        private fun compare(a:String,b:String):Int {
            val x=version(a) ?: return -1;val y=version(b) ?: return 1
            for(i in 0..2)if(x[i]!=y[i])return x[i].compareTo(y[i]);return 0
        }
        private fun officialAsset(url:String):Boolean=runCatching {URI(url).let {
            it.scheme=="https" && it.host=="github.com" && it.userInfo==null && it.port==-1 && it.fragment==null && it.query==null && it.path.startsWith("/Zita-Go/Pare/releases/download/")
        }}.getOrDefault(false)
        fun select(json:String,current:String):DesktopUpdateCandidate? {
            val candidates=wire.parseToJsonElement(json).jsonArray.flatMap {release->
                val row=release.jsonObject
                if(row["draft"]?.jsonPrimitive?.booleanOrNull!=false)return@flatMap emptyList()
                row["assets"]?.jsonArray.orEmpty().mapNotNull {asset->
                    val a=asset.jsonObject
                    val name=a["name"]?.jsonPrimitive?.content.orEmpty()
                    val v=Regex("^Step-(\\d+\\.\\d+\\.\\d+)(?:-windows-x64)?\\.exe$",RegexOption.IGNORE_CASE).matchEntire(name)?.groupValues?.get(1) ?: return@mapNotNull null
                    val url=a["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
                    val size=a["size"]?.jsonPrimitive?.longOrNull ?: 0
                    val hash=a["digest"]?.jsonPrimitive?.content?.removePrefix("sha256:").orEmpty()
                    if(compare(v,current)<=0 || !officialAsset(url) || size !in 1..MAX_INSTALLER || !hash.matches(Regex("[a-fA-F0-9]{64}")))return@mapNotNull null
                    DesktopUpdateCandidate(v,row["body"]?.jsonPrimitive?.content.orEmpty().take(8_000),url,size,hash.lowercase())
                }
            }
            return candidates.maxWithOrNull {a,b->compare(a.version,b.version)}
        }
        fun verify(file:Path,candidate:DesktopUpdateCandidate) {
            require(Files.isRegularFile(file) && Files.size(file)==candidate.size) {"安装包大小不符，未启动安装"}
            val digest=MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use {input->val buffer=ByteArray(64*1024);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
            require(digest.digest().joinToString("") {"%02x".format(it)}.equals(candidate.sha256,true)) {"安装包校验失败，未启动安装"}
        }
        private fun connection(url:String,metadata:Boolean=false):HttpURLConnection {
            var target=URI(url)
            repeat(6) {
                require(target.scheme=="https" && target.userInfo==null && target.port in setOf(-1,443) && target.host in setOf("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com","github-releases.githubusercontent.com")) {"更新下载跳转到未获准地址"}
                val c=target.toURL().openConnection() as HttpURLConnection
                c.instanceFollowRedirects=false;c.connectTimeout=15_000;c.readTimeout=20_000
                c.setRequestProperty("User-Agent","Step-Windows/$CURRENT_VERSION")
                if(metadata){c.setRequestProperty("Accept","application/vnd.github+json");c.setRequestProperty("X-GitHub-Api-Version","2022-11-28")}
                val status=c.responseCode
                if(status in setOf(301,302,303,307,308)) {
                    val location=c.getHeaderField("Location");c.disconnect();target=target.resolve(requireNotNull(location))
                } else {if(status!=200){c.disconnect();error("官方更新服务暂不可用（$status）")};return c}
            }
            error("更新下载跳转次数过多")
        }
        val CURRENT_VERSION:String=System.getProperty("step.version","0.1.0")
    }
}
