package com.step.desktop

import com.pare.app.*
import org.junit.Assert.*
import org.junit.Test

class DesktopSyncStatusTest {
    private val session=Session("fixture-owner","fixture@example.invalid","fictional-access","fictional-refresh",9_999_999_999)
    private val enabled=SyncState(owner=session.owner,enabled=true)
    private fun present(state:SyncState=enabled,loggedIn:Boolean=true,busy:Boolean=false,ready:Boolean=true,status:String="已同步",initialized:Boolean=true)=
        desktopSyncPresentation(state,if(loggedIn)session else null,initialized,busy,ready,status)

    @Test fun enablingSyncDoesNotClaimAnUnverifiedLibraryIsSynced() {
        assertEquals(DesktopSyncPhase.WAITING,present(status="等待同步").phase)
        assertEquals(DesktopSyncPhase.SYNCED,present().phase)
        assertEquals(DesktopSyncPhase.WAITING,present(ready=false).phase)
        assertEquals(DesktopSyncPhase.READING,present(initialized=false).phase)
        assertEquals(DesktopSyncPhase.LOCAL,present(loggedIn=false).phase)
    }
    @Test fun localEditsAndUnconfirmedRequestsOverrideStaleSuccessText() {
        val dirty=Entity("task","fixture",dirty=true)
        assertEquals(DesktopSyncPhase.WAITING,present(enabled.copy(items=mapOf(dirty.key to dirty))).phase)
        assertEquals(DesktopSyncPhase.WAITING,present(enabled.copy(pending=Push("fixture-operation",enabled.device,emptyList()))).phase)
        val conflicting=dirty.copy(conflict=Remote("task","fixture",1,device="fixture-cloud",sequence=1))
        assertEquals(DesktopSyncPhase.CONFLICT,present(enabled.copy(items=mapOf(conflicting.key to conflicting))).phase)
    }
    @Test fun progressConsentAndPausedStatesRemainDistinct() {
        val disabled=enabled.copy(enabled=false)
        assertEquals(DesktopSyncPhase.PREVIEW_PENDING,present(disabled,ready=false,status="同步未开启").phase)
        assertEquals(DesktopSyncPhase.AWAITING_CONSENT,present(disabled,status="已拉取预览 · 同步未开启").phase)
        assertEquals(DesktopSyncPhase.PAUSED,present(disabled,ready=false,status="同步已暂停 · 变更保留在本机").phase)
        assertEquals(DesktopSyncPhase.PULLING,present(disabled,busy=true,ready=false,status="正在同步").phase)
        assertEquals(DesktopSyncPhase.SYNCING,present(busy=true,status="正在同步").phase)
        assertEquals(DesktopSyncPhase.AUTHENTICATING,present(loggedIn=false,busy=true,status="正在登录").phase)
    }
    @Test fun failuresRemainVisibleWithNoLocalQueueAndBeforeConsent() {
        listOf("等待联网或重试 · 变更已保存在本机","登录已失效或请求被拒绝，请重新登录","连接超时 · 请重试，本机变更已保留","同步已停止，请检查账号或重新登录；本机变更已保留").forEach {failure->
            val state=present(status=failure)
            assertEquals(DesktopSyncPhase.ERROR,state.phase)
            assertTrue(state.detail.contains(failure))
            assertEquals(DesktopSyncPhase.ERROR,present(enabled.copy(enabled=false),ready=false,status=failure).phase)
        }
        assertEquals(DesktopSyncPhase.ERROR,present(initialized=false,status="本机数据或登录状态读取失败，原数据已保留；请勿清除应用数据").phase)
    }
}
