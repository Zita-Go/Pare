package com.step.desktop

/** Session-only equality tokens, never titles or browsing history. A new process gets a new salt. */
internal class DesktopBrowserIdentity {
    private val salt=ByteArray(32).also {java.security.SecureRandom().nextBytes(it)}
    fun supports(executable:String)=executable.lowercase(java.util.Locale.ROOT) in setOf("chrome.exe","msedge.exe","firefox.exe","brave.exe","vivaldi.exe","opera.exe","arc.exe")
    @Synchronized fun reset() {java.security.SecureRandom().nextBytes(salt)}
    @Synchronized fun token(executable:String,title:CharArray,length:Int):String? {
        var encoded:java.nio.ByteBuffer?=null
        try {
            if(!supports(executable) || length<=0)return null
            encoded=Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(title,0,length.coerceAtMost(title.size)))
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            digest.update(salt);digest.update(encoded)
            return digest.digest().joinToString("") {"%02x".format(it)}
        } finally {title.fill('\u0000');if(encoded?.hasArray()==true)encoded.array().fill(0)}
    }
}

/** Counts opaque foreground-window transitions in memory; never obtains names, screenshots or URLs. */
internal class DesktopAttentionPolicy {
    private val switches=java.util.ArrayDeque<Pair<Long,String>>()
    private var lastTarget:String?=null
    private var wasIdle=false
    private var quietUntil=0L
    private var day=Long.MIN_VALUE
    private var count=0
    @Synchronized fun snooze(now:Long,minutes:Int) {require(minutes in 1..10080);quietUntil=maxOf(quietUntil,now+minutes*60_000L);switches.clear()}
    @Synchronized fun clearSignals() {switches.clear();lastTarget=null;wasIdle=false}
    @Synchronized fun observe(now:Long,idleMillis:Long,window:Long,hasFocus:Boolean,browserToken:String?=null,hasActionableTask:Boolean=false,lastMeaningfulProgressAt:Long?=null):String? {
        if(day!=now/86_400_000) {day=now/86_400_000;count=0}
        val target=if(window==0L)null else "$window:${browserToken.orEmpty()}"
        if(lastTarget!=null && target!=null && target!=lastTarget && idleMillis<20_000)switches.addLast(now to target)
        if(target!=null)lastTarget=target
        while(switches.isNotEmpty() && now-switches.first.first>120_000)switches.removeFirst()
        if(idleMillis>=300_000) {wasIdle=true;return null}
        val returned=wasIdle && idleMillis<20_000
        if(returned)wasIdle=false
        if(now<quietUntil || count>=3)return null
        val distinct=switches.map {it.second}.distinct().size
        val revisiting=switches.size>=12 && distinct in 2..5 && switches.size-distinct>=8
        val stalled=hasActionableTask && lastMeaningfulProgressAt!=null && now-lastMeaningfulProgressAt>=10*60_000
        val text=when {
            hasFocus && revisiting->"最近在几个窗口或页面间多次往返。可以回到当前专注，或先记下卡点；这不代表系统理解了你正在做什么。"
            stalled && revisiting->"最近在几个窗口或页面间多次往返，Step 中暂时没有新的推进记录。需要的话，可以选一个最小下一步；这不代表你没有在工作。"
            returned && (hasFocus || hasActionableTask)->"回到电脑了。可以从今天重点的最小下一步开始。"
            else->null
        }
        if(text!=null){quietUntil=now+60*60_000;count++;switches.clear()}
        return text
    }
}
