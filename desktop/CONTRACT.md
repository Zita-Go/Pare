# Desktop integration contract

Package `com.step.desktop`; shared domain remains `com.pare.app`.

Root owns Gradle settings/root/app build, scripts, Main.kt, docs, CI and approved resources. Core agent owns Android pure extraction and desktop/core build/test selection. Runtime agent owns all Desktop* runtime/persistence/security files except DesktopUi.kt/DesktopTheme.kt, and app tests. UI agent owns DesktopUi.kt/DesktopTheme.kt and UI helper files prefixed Ui*. Communicate changes rather than editing another owner's files.

Runtime public surface (native/runtime agent implements):

```kotlin
data class DesktopConfig(val syncUrl:String="",val syncPublicKey:String="")
data class DesktopAISettings(val config:MobileAIConfig=MobileAIConfig(),val hasKey:Boolean=false)
class DesktopRuntime(val home:Path,val config:DesktopConfig,val notify:(String,String)->Unit={_,_->}):AutoCloseable {
 val engine:SyncEngine
 val execution:ExecutionController
 val executionReady:StateFlow<Boolean>
 val error:MutableStateFlow<String?>
 val aiSettings:StateFlow<DesktopAISettings>
 val aiHistory:StateFlow<List<MobileAITrace>>
 suspend fun initialize()
 suspend fun edit(change:(SyncState)->SyncState):Result<Unit>
 suspend fun execute(command:(ExecutionController)->Unit):Result<Unit>
 fun now():ExecutionMoment
 suspend fun saveAI(config:MobileAIConfig,key:String?):Result<Unit> // null keeps matching-service key; empty removes
 suspend fun generateAI(input:MobileAIRequest):Result<MobileAIProposal>
 suspend fun generatePlan(input:PlanningAIRequest):Result<PlanningAIProposal>
 suspend fun exportBackup(destination:Path):Result<Unit> // content only, no credentials/raw AI history
 fun requestSync()
 override fun close() // persist/pause focus, cancel services, close resources; no user-data deletion
}
```

UI: `@Composable fun DesktopUi(runtime:DesktopRuntime,onQuickCapture:()->Unit,onRequestClose:()->Unit)` plus `@Composable fun QuickCaptureUi(runtime:DesktopRuntime,onClose:()->Unit)`. Uses runtime.engine state/session/busy/status/initialized/previewReady flows and existing authenticate/enable/pause/signOut. All mutations via runtime.edit; close editors only after success. Root provides notification callback and Window/Tray integration. UI uses Windows-oriented labels; no Android-specific Context/ViewModel/R imports. No runtime/real task access in previews/tests.
