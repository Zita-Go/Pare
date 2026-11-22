#requires -Version 5.1
<#
Runs the already-packaged executable against a NEW fictional profile. No build is performed.
Use Windows PowerShell 5.1 -STA; pwsh callers are forwarded to it automatically.
Only disposable GitHub-hosted Windows runners receive synthetic input or a lock request.
Report statuses: pass = the stated observation, fail = attempted check failed,
unsupported = prerequisite or observation unavailable. Unsupported is never a release pass.

References read when designing these checks:
https://learn.microsoft.com/windows/win32/api/winuser/nf-winuser-openinputdesktop
https://learn.microsoft.com/windows/win32/api/winuser/nf-winuser-sendinput
https://learn.microsoft.com/windows/win32/api/winuser/nf-winuser-getdpiforwindow
https://learn.microsoft.com/windows-hardware/manufacture/desktop/default-input-locales-for-windows-language-packs
https://learn.microsoft.com/windows/win32/api/msctf/nf-msctf-itfinputprocessorprofilemgr-activateprofile
https://learn.microsoft.com/uwp/api/windows.ui.notifications.toastnotificationhistory.gethistory
https://learn.microsoft.com/windows/win32/api/winuser/nf-winuser-lockworkstation
https://learn.microsoft.com/windows/win32/termserv/wm-wtssession-change
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Executable,
    [Parameter(Mandatory=$true)][string]$DataDirectory,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [switch]$AttemptLock
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
if ($env:OS -ne 'Windows_NT') { throw 'Windows only; this script never controls the host Mac.' }
foreach ($value in @($Executable,$DataDirectory,$OutputDirectory,$PSCommandPath)) {
    if ($value -match '["\r\n]') { throw 'Paths containing quotes or newlines are unsupported.' }
}
# WinRT projections used by the packaged helper need Windows PowerShell, not pwsh/.NET Core.
if ($PSVersionTable.PSEdition -ne 'Desktop' -or [Threading.Thread]::CurrentThread.ApartmentState -ne 'STA') {
    $psi = New-Object Diagnostics.ProcessStartInfo
    $psi.FileName = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
    $psi.UseShellExecute = $false
    $psi.Arguments = '-NoProfile -STA -ExecutionPolicy RemoteSigned -File "' + $PSCommandPath + '" -Executable "' + $Executable + '" -DataDirectory "' + $DataDirectory + '" -OutputDirectory "' + $OutputDirectory + '"'
    if ($AttemptLock) { $psi.Arguments += ' -AttemptLock' }
    $psi.EnvironmentVariables['PSModulePath'] = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\Modules"
    $child = [Diagnostics.Process]::Start($psi)
    if (!$child.WaitForExit(480000)) { $child.Kill(); throw 'Acceptance process exceeded eight minutes; inspect its partial JSON reports.' }
    exit $child.ExitCode
}
$Executable = [IO.Path]::GetFullPath($Executable)
$DataDirectory = [IO.Path]::GetFullPath($DataDirectory).TrimEnd('\')
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory).TrimEnd('\')
if (!(Test-Path -LiteralPath $Executable -PathType Leaf)) { throw 'Packaged executable not found.' }
if ((Test-Path -LiteralPath $DataDirectory) -and @(Get-ChildItem -LiteralPath $DataDirectory -Force).Count -gt 0) { throw 'Refusing nonempty data directory; use a fresh fictional profile.' }
if ($DataDirectory -eq $OutputDirectory -or $OutputDirectory.StartsWith($DataDirectory+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Output must be outside the fresh data directory.' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)
$checks = [ordered]@{}
$probe = [ordered]@{}
$processes = New-Object 'Collections.Generic.List[Diagnostics.Process]'
$disposable = $env:GITHUB_ACTIONS -eq 'true' -and $env:RUNNER_ENVIRONMENT -eq 'github-hosted'
$profileIdHash = [Security.Cryptography.SHA256]::Create()
try { $profileId = ([BitConverter]::ToString($profileIdHash.ComputeHash($utf8.GetBytes($DataDirectory)))).Replace('-','').ToLowerInvariant().Substring(0,12) }
finally { $profileIdHash.Dispose() }
$appId = 'Step.Desktop.'+$profileId
$helper = Join-Path $DataDirectory 'windows-system\step-windows.ps1'
$started = [DateTimeOffset]::UtcNow
$helperReady = $false
$uiProcess = $null

function Write-JsonFile([string]$path,$value) {
    $temporary = $path+'.tmp'
    [IO.File]::WriteAllText($temporary,($value | ConvertTo-Json -Depth 12),$utf8)
    Move-Item -LiteralPath $temporary -Destination $path -Force
}
function Save-Report {
    $values = @($checks.Values)
    $overall = if (@($values | Where-Object {$_.status -eq 'fail'}).Count) {'fail'} elseif (@($values | Where-Object {$_.status -eq 'unsupported'}).Count) {'incomplete'} else {'pass'}
    Write-JsonFile (Join-Path $OutputDirectory 'interactive-acceptance.json') ([ordered]@{
        schema=1; startedUtc=$started.ToString('o'); updatedUtc=[DateTimeOffset]::UtcNow.ToString('o');
        overall=$overall; executableSha256=(Get-FileHash -LiteralPath $Executable -Algorithm SHA256).Hash.ToLowerInvariant();
        fixtureOnly=$true; checks=$checks
    })
}
function Result([string]$name,[ValidateSet('pass','fail','unsupported')][string]$status,[string]$detail,$evidence=$null) {
    $checks[$name] = [ordered]@{status=$status;detail=$detail}
    if ($null -ne $evidence) {$checks[$name]['evidence']=$evidence}
    Save-Report
    Write-Host ($name+': '+$status+' - '+$detail)
}
function Error-Detail($failure) { return ($failure.Exception.GetType().Name+': '+$failure.Exception.Message).Substring(0,[Math]::Min(260,($failure.Exception.GetType().Name+': '+$failure.Exception.Message).Length)) }
function Wait-Until([scriptblock]$condition,[int]$seconds=20) {
    $timer=[Diagnostics.Stopwatch]::StartNew()
    do { if (& $condition) {return $true}; Start-Sleep -Milliseconds 200 } while ($timer.Elapsed.TotalSeconds -lt $seconds)
    return $false
}
function Start-Step([string[]]$arguments) {
    $quoted = @($arguments | ForEach-Object { '"'+$_+'"' }) -join ' '
    $p = Start-Process -FilePath $Executable -ArgumentList $quoted -PassThru
    $processes.Add($p)
    return $p
}
function Stop-Step($process) {
    if ($null -eq $process) {return}
    if ($process.HasExited) {
        $script:lastExitEvidence=@{processId=$process.Id;confirmedExited=$true;forced=$false;exitObservedUtc=[DateTimeOffset]::UtcNow.ToString('o')}
        return
    }
    foreach ($w in @([StepAcceptanceNative]::Windows($process.Id))) {
        if ($w.Title -eq 'Step') {[void][StepAcceptanceNative]::PostMessage([IntPtr]$w.Handle,0x10,[IntPtr]::Zero,[IntPtr]::Zero)}
    }
    $forced=$false
    if (!$process.WaitForExit(10000)) {
        $forced=$true;$process.Kill()
        if (!$process.WaitForExit(5000)) {throw 'Isolated Step still has not exited after termination; after-exit acceptance is invalid.'}
    }
    if (!$process.HasExited) {throw 'Isolated Step exit could not be confirmed.'}
    $script:lastExitEvidence=@{processId=$process.Id;confirmedExited=$true;forced=$forced;exitObservedUtc=[DateTimeOffset]::UtcNow.ToString('o')}
}
function Get-Inbox {
    $path=Join-Path $DataDirectory 'sync-state.json'
    if (!(Test-Path -LiteralPath $path)) {return @()}
    $snapshot=Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    return @($snapshot.state.items.PSObject.Properties | ForEach-Object {$_.Value} | Where-Object {$_.kind -eq 'inbox'})
}
function Focus-Step([long]$handle) {
    [void][StepAcceptanceNative]::ShowWindow([IntPtr]$handle,9)
    [void][StepAcceptanceNative]::SetForegroundWindow([IntPtr]$handle)
    if (!(Wait-Until { [StepAcceptanceNative]::GetForegroundWindow().ToInt64() -eq $handle } 3)) {throw 'Step could not acquire the foreground; input was not sent.'}
}
function Open-Capture {
    [StepAcceptanceNative]::Chord([ushort[]]@(0x11,0x10,0x20))
    if (!(Wait-Until { @([StepAcceptanceNative]::Windows($uiProcess.Id) | Where-Object {$_.Title -like 'Step*随手记*'}).Count -gt 0 } 8)) {throw 'Ctrl+Shift+Space did not reveal the Step quick-capture window.'}
    return @([StepAcceptanceNative]::Windows($uiProcess.Id) | Where-Object {$_.Title -like 'Step*随手记*'})[0]
}
function Invoke-PackagedHelper([string]$operation,[hashtable]$payload) {
    $request=@{operation=$operation;home=$DataDirectory;executable=$Executable;profile=$profileId;appId=$appId;scheme=('step-'+$profileId)}
    foreach ($key in $payload.Keys) {$request[$key]=$payload[$key]}
    $requestPath=Join-Path $OutputDirectory ('helper-'+$operation+'.json')
    Write-JsonFile $requestPath $request
    $stdout=Join-Path $OutputDirectory ('helper-'+$operation+'.out')
    $stderr=Join-Path $OutputDirectory ('helper-'+$operation+'.err')
    try {
        $p=Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','RemoteSigned','-File',('"'+$helper+'"'),'-InputFile',('"'+$requestPath+'"')) -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
        if (!$p.WaitForExit(35000)) {$p.Kill();throw 'Packaged notification helper timed out.'}
        if ($p.ExitCode -ne 0) {throw ('Packaged notification helper exit '+$p.ExitCode)}
        return (Get-Content -LiteralPath $stdout -Raw -Encoding UTF8).Trim()
    } finally {foreach ($path in @($requestPath,$stdout,$stderr)) {Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue}}
}
function Get-ToastEvidence([string]$tag,[string]$marker,[bool]$tryVisible) {
    $history=$false;$historyError=$null;$visible=$false
    try {$history=@([Windows.UI.Notifications.ToastNotificationManager]::History.GetHistory($appId) | Where-Object {$_.Tag -eq $tag -and $_.Group -eq $profileId}).Count -gt 0}
    catch {$historyError=Error-Detail $_}
    if ($tryVisible) {$visible=[StepAcceptanceNative]::VisibleNotificationText($marker,2500)}
    return @{historyContainsTag=$history;visibleTextObserved=$visible;historyError=$historyError}
}
function Prepare-Reminder([int]$seconds=50) {
    $state=(Get-Content -LiteralPath (Join-Path $DataDirectory 'sync-state.json') -Raw -Encoding UTF8 | ConvertFrom-Json).state
    $task=@($state.items.PSObject.Properties | ForEach-Object {$_.Value} | Where-Object {$_.kind -eq 'task'})[0]
    $at=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+$seconds*1000
    # Valid offline test fixture only. Step itself computes occurrence tokens and the OS task.
    Write-JsonFile (Join-Path $DataDirectory 'execution.json') @{
        version=1;owner=$null;device=$state.device;focus=$null;
        external=@(@{id=[guid]::NewGuid().ToString();taskId=$task.id;startedAtMillis=($at-60000);paused=$true;nextCheckAtMillis=$at})
    }
    Write-JsonFile (Join-Path $DataDirectory 'system-settings.json') @{systemNotifications=$true;afterExitReminders=$true}
    $script:uiProcess=Start-Step @('--data-dir',$DataDirectory)
    if (!(Wait-Until {
        try {
            $data=Get-Content -LiteralPath (Join-Path $DataDirectory 'reminders.json') -Raw -Encoding UTF8 | ConvertFrom-Json
            return @($data.ledger.scheduled.PSObject.Properties | Where-Object {$_.Value.atMillis -eq $at}).Count -gt 0 -and (Test-Path -LiteralPath $helper)
        } catch {return $false}
    } 30)) {throw 'Step did not produce a scheduled occurrence from the fictional run.'}
    $script:helperReady=$true
    $ledger=(Get-Content -LiteralPath (Join-Path $DataDirectory 'reminders.json') -Raw -Encoding UTF8 | ConvertFrom-Json).ledger
    $event=@($ledger.scheduled.PSObject.Properties | Where-Object {$_.Value.atMillis -eq $at})[0].Value
    $token=$event.fingerprint+':0:'+$event.suffix
    if (!(Wait-Until {
        try {$service=New-Object -ComObject Schedule.Service;$service.Connect();return @($service.GetFolder('\Step-'+$profileId).GetTasks(1)).Count -eq 1} catch {return $false}
    } 12)) {throw 'Step did not register its scheduled task.'}
    $service=New-Object -ComObject Schedule.Service;$service.Connect()
    $task=@($service.GetFolder('\Step-'+$profileId).GetTasks(1))[0]
    $definition=$task.Definition
    if ($definition.Principal.LogonType -ne 3 -or $definition.Actions.Item(1).Path -ne $Executable -or !$definition.Actions.Item(1).Arguments.Contains('--deliver-reminder') -or !$definition.Actions.Item(1).Arguments.Contains($DataDirectory)) {throw 'Unexpected scheduled-task identity or action.'}
    return @{atMillis=$at;token=$token;sourceKey=$event.sourceKey;taskName=$task.Name;triggerUtc=$definition.Triggers.Item(1).StartBoundary;interactiveLogon=$true}
}
function Is-ReminderHandled($event) {
    $ledger=(Get-Content -LiteralPath (Join-Path $DataDirectory 'reminders.json') -Raw -Encoding UTF8 | ConvertFrom-Json).ledger
    return @($ledger.handled) -contains $event.token
}
function Wait-Reminder($event,[int]$seconds=90) {
    $taskObservation=@{lastRunUtc=$null;lastTaskResult=$null}
    $handled=Wait-Until {
        # Step removes a handled task itself. Observe real Scheduler state while it exists,
        # but preserve null if that short lifetime prevents sampling (never invent a result).
        if ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() -ge $event.atMillis) {
            try {
                $service=New-Object -ComObject Schedule.Service;$service.Connect()
                $osTask=$service.GetFolder('\Step-'+$profileId).GetTask($event.taskName)
                if ($osTask.LastRunTime.Year -gt 2000) {$taskObservation.lastRunUtc=$osTask.LastRunTime.ToUniversalTime().ToString('o');$taskObservation.lastTaskResult=$osTask.LastTaskResult}
            } catch {}
        }
        try {
            $ledger=(Get-Content -LiteralPath (Join-Path $DataDirectory 'reminders.json') -Raw -Encoding UTF8 | ConvertFrom-Json).ledger
            return @($ledger.handled) -contains $event.token
        } catch {return $false}
    } $seconds
    $evidence=@{handled=$handled;exitEvidence=$event.exitEvidence;taskSchedulerObservation=$taskObservation;dueUtc=[DateTimeOffset]::FromUnixTimeMilliseconds($event.atMillis).ToString('o');observedUtc=[DateTimeOffset]::UtcNow.ToString('o');scheduledAction='packaged Step --deliver-reminder';manuallyInvoked=$false}
    if ($handled) {
        $sha=[Security.Cryptography.SHA256]::Create()
        try {$tag=([BitConverter]::ToString($sha.ComputeHash($utf8.GetBytes($event.token)))).Replace('-','').ToLowerInvariant().Substring(0,16)} finally {$sha.Dispose()}
        for ($attempt=0;$attempt -lt 12;$attempt++) {
            $evidence.toast=Get-ToastEvidence $tag 'Windows smoke fixture' $false
            if ($evidence.toast.historyContainsTag -or $evidence.toast.historyError) {break}
            Start-Sleep -Seconds 1
        }
    }
    return $evidence
}

try {
    Add-Type -AssemblyName System.Windows.Forms,System.Drawing,UIAutomationClient,UIAutomationTypes,WindowsBase
    $references=@('System.dll','System.Core.dll',[Windows.Forms.Form].Assembly.Location,[Drawing.Bitmap].Assembly.Location,[Windows.Automation.AutomationElement].Assembly.Location,[Windows.Automation.AutomationIdentifier].Assembly.Location,[Windows.Rect].Assembly.Location)
    Add-Type -Path (Join-Path $PSScriptRoot 'windows-interactive-native.cs') -ReferencedAssemblies $references
    $desktop=[StepAcceptanceNative]::Desktop()
    $probe.desktop=$desktop
    $probe.disposableHostedRunner=$disposable
    $probe.osVersion=[Environment]::OSVersion.VersionString
    $probe.displays=@([StepAcceptanceNative]::Displays())
    $probe.screens=@([Windows.Forms.Screen]::AllScreens | ForEach-Object {@{device=$_.DeviceName;width=$_.Bounds.Width;height=$_.Bounds.Height;left=$_.Bounds.Left;top=$_.Bounds.Top}})
    try {$probe.physicalMonitorCount=@(Get-CimInstance -Namespace root\wmi -ClassName WmiMonitorID -OperationTimeoutSec 3 -ErrorAction Stop).Count} catch {$probe.physicalMonitorCount=0;$probe.physicalMonitorProbeError=Error-Detail $_}
    try {$probe.inputLanguages=@(Get-WinUserLanguageList | ForEach-Object {@{language=$_.LanguageTag;inputMethodTips=@($_.InputMethodTips)}})} catch {$probe.inputLanguages=@();$probe.languageProbeError=Error-Detail $_}
    $pinyinTip='0804:{81D4E9C9-1D3B-41BC-9E6C-4B40BF79E35E}{FA550B04-5AD7-411F-A5AC-CA038EC515D7}'
    $pinyinAvailable=@($probe.inputLanguages | Where-Object {$_.inputMethodTips -contains $pinyinTip}).Count -gt 0
    $probe.pinyinConfigured=$pinyinAvailable
    $probe.pinyinTipRegistered=Test-Path 'HKLM:\SOFTWARE\Microsoft\CTF\TIP\{81D4E9C9-1D3B-41BC-9E6C-4B40BF79E35E}'
    try {
        [Windows.UI.Notifications.ToastNotificationManager,Windows.UI.Notifications,ContentType=WindowsRuntime] | Out-Null
        $probe.notificationSetting=[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($appId).Setting.ToString()
    } catch {$probe.notificationSetting='unavailable';$probe.notificationError=Error-Detail $_}
    try {$scheduler=New-Object -ComObject Schedule.Service;$scheduler.Connect();$probe.taskSchedulerConnected=$true} catch {$probe.taskSchedulerConnected=$false}
    Write-JsonFile (Join-Path $OutputDirectory 'interactive-probe.json') $probe
    $interactive=$desktop.UserInteractive -and $desktop.SessionId -gt 0 -and $desktop.WtsState -eq 0 -and $desktop.InputDesktopAccessible -and $desktop.InputDesktop -eq $desktop.ThreadDesktop -and $probe.screens.Count -gt 0
    Result 'interactive_desktop' $(if($interactive){'pass'}else{'unsupported'}) 'Probe requires an active WTS session and matching accessible input/thread desktops.' $desktop
    Result 'input_method_inventory' $(if($probe.Contains('languageProbeError')){'unsupported'}else{'pass'}) 'Installed/configured input language profiles were queried; Unicode injection is not an IME check.' @{pinyinConfigured=$pinyinAvailable;profiles=$probe.inputLanguages}
    Result 'notification_platform' $(if($probe.notificationSetting -eq 'unavailable'){'unsupported'}else{'pass'}) 'Queried OS notification setting; this does not prove delivery or visibility.' @{setting=$probe.notificationSetting;taskScheduler=$probe.taskSchedulerConnected}
    if (!$disposable) {
        Result 'runner_scope' 'unsupported' 'Synthetic input and profile mutation are limited to a disposable GitHub-hosted runner.'
        foreach ($name in @('window_open','quick_capture_hotkey','unicode_edit_save','microsoft_pinyin','actual_window_dpi','multi_monitor_scaling','notification_history','notification_visible','scheduled_delivery','lock_screen_delivery')) {Result $name 'unsupported' 'No interactive operations attempted outside the disposable runner.'}
        return
    }
    $seed=Start-Step @('--self-test','--data-dir',$DataDirectory)
    if (!$seed.WaitForExit(45000)) {$seed.Kill();throw 'Packaged fixture self-test timed out.'}
    if ($seed.ExitCode -ne 0 -or !(Test-Path -LiteralPath (Join-Path $DataDirectory 'smoke-result.json'))) {throw 'Packaged fixture self-test failed.'}
    [IO.File]::WriteAllText((Join-Path $DataDirectory 'window.properties'),"keepInTray=false`n",$utf8)
    if ($interactive) {
        try {
            $uiProcess=Start-Step @('--data-dir',$DataDirectory)
            if (!(Wait-Until { @([StepAcceptanceNative]::Windows($uiProcess.Id) | Where-Object {$_.Title -eq 'Step'}).Count -gt 0 } 35)) {throw 'No visible Step main window within 35 seconds.'}
            $main=@([StepAcceptanceNative]::Windows($uiProcess.Id) | Where-Object {$_.Title -eq 'Step'})[0]
            Focus-Step $main.Handle
            Start-Sleep -Seconds 2
            Result 'window_open' 'pass' 'Packaged Step has a visible foreground main window.' $main
            Result 'actual_window_dpi' 'pass' 'Read GetDpiForWindow and actual window awareness; no Compose density override was used.' @{dpi=$main.Dpi;dpiAwareness=$main.DpiAwareness;displayCount=$probe.screens.Count}
            try {[StepAcceptanceNative]::CaptureWindow([IntPtr]$main.Handle,(Join-Path $OutputDirectory 'step-main-native.png'))} catch {Result 'main_screenshot' 'unsupported' (Error-Detail $_)}
            $moves=@()
            foreach ($screen in [Windows.Forms.Screen]::AllScreens) {
                [void][StepAcceptanceNative]::SetWindowPos([IntPtr]$main.Handle,[IntPtr]::Zero,($screen.WorkingArea.Left+10),($screen.WorkingArea.Top+10),0,0,0x15)
                Start-Sleep -Milliseconds 500
                $moves+=@{display=$screen.DeviceName;dpi=[StepAcceptanceNative]::GetDpiForWindow([IntPtr]$main.Handle)}
            }
            $distinct=@($moves | ForEach-Object {$_.dpi} | Select-Object -Unique).Count
            Result 'multi_monitor_scaling' 'unsupported' 'Actual monitor moves and DPI samples recorded; complete visual/layout acceptance at different system scales still needs human inspection.' @{moves=$moves;distinctDpi=$distinct;physicalMonitorCount=$probe.physicalMonitorCount}
            Focus-Step $main.Handle
            $capture=Open-Capture
            Result 'quick_capture_hotkey' $(if($capture.Foreground){'pass'}else{'fail'}) 'Sent native Ctrl+Shift+Space; checked quick-capture visibility and foreground without forcing focus.' $capture
            Focus-Step $capture.Handle
            [StepAcceptanceNative]::UnicodeText('虚构草稿：需要替换')
            [StepAcceptanceNative]::Chord([ushort[]]@(0x11,0x41))
            $savedText='验收中文：整理想法，迈出最小一步。'
            [StepAcceptanceNative]::UnicodeText($savedText)
            try {[StepAcceptanceNative]::CaptureWindow([IntPtr]$capture.Handle,(Join-Path $OutputDirectory 'step-chinese-text-native.png'))} catch {Result 'text_screenshot' 'unsupported' (Error-Detail $_)}
            [StepAcceptanceNative]::Chord([ushort[]]@(0x11,0x0D))
            $saved=Wait-Until { @(Get-Inbox | Where-Object {$_.payload.content.text -eq $savedText}).Count -eq 1 } 12
            Result 'unicode_edit_save' $(if($saved){'pass'}else{'fail'}) 'KEYEVENTF_UNICODE text, native Ctrl+A replacement and Ctrl+Enter save checked against isolated persisted inbox; this is NOT an IME test.' @{exactPersistedText=$saved}
            if ($pinyinAvailable) {
                try {
                    Focus-Step $main.Handle
                    $capture=Open-Capture;Focus-Step $capture.Handle
                    $originalLayout=[StepAcceptanceNative]::WindowKeyboardLayout([IntPtr]$capture.Handle)
                    $before=@(Get-Inbox | ForEach-Object {$_.id})
                    if (![StepAcceptanceNative]::RequestChineseInput([IntPtr]$capture.Handle)) {throw 'No loaded Chinese input locale accepted the language-change request.'}
                    Start-Sleep -Milliseconds 400
                    $activation=[StepAcceptanceNative]::ActivatePinyin()
                    Start-Sleep -Milliseconds 700
                    $targetLayout=[StepAcceptanceNative]::WindowKeyboardLayout([IntPtr]$capture.Handle)
                    if ($activation -ne 0 -or ($targetLayout -band 0xffff) -ne 0x804) {throw 'Configured Microsoft Pinyin did not activate for the Step window.'}
                    [StepAcceptanceNative]::AsciiKeys('nihao')
                    Start-Sleep -Milliseconds 600
                    $candidate=[StepAcceptanceNative]::CandidateVisible()
                    [StepAcceptanceNative]::Chord([ushort[]]@(0x20))
                    Start-Sleep -Milliseconds 300
                    [StepAcceptanceNative]::Chord([ushort[]]@(0x11,0x0D))
                    $converted=Wait-Until { @(Get-Inbox | Where-Object {$before -notcontains $_.id -and $_.payload.content.text -match '[\u4e00-\u9fff]'}).Count -gt 0 } 12
                    Result 'microsoft_pinyin' $(if($converted){'pass'}else{'fail'}) 'Real Microsoft Pinyin activated; only Latin virtual keys nihao and candidate commit were sent, then persisted Chinese was checked.' @{activationHresult=$activation;targetLanguageId=($targetLayout -band 0xffff);candidateWindowObserved=$candidate;chinesePersisted=$converted}
                    Result 'ime_candidate_layout' 'unsupported' 'Candidate-window observation alone cannot validate placement, clipping or all composition interactions.' @{candidateWindowObserved=$candidate}
                } catch {Result 'microsoft_pinyin' 'fail' (Error-Detail $_)}
                finally {if (Get-Variable originalLayout -ErrorAction SilentlyContinue) {[void][StepAcceptanceNative]::RestoreKeyboard($originalLayout)}}
            } else {Result 'microsoft_pinyin' 'unsupported' 'Microsoft Pinyin is not in the configured Windows input method inventory; no Unicode result is counted as IME acceptance.'}
        } catch {Result 'interactive_input' 'fail' (Error-Detail $_)}
        finally {Stop-Step $uiProcess;$uiProcess=$null}
    } else {
        foreach ($name in @('window_open','quick_capture_hotkey','unicode_edit_save','microsoft_pinyin','actual_window_dpi','multi_monitor_scaling')) {Result $name 'unsupported' 'Active interactive desktop prerequisite failed; see probe JSON.'}
    }
    # Real notification and time-trigger tests are independent of keyboard automation.
    try {
        $event=Prepare-Reminder 70
        $registered=Invoke-PackagedHelper 'inspect' @{} | ConvertFrom-Json
        Result 'notification_registration' $(if($registered.shortcut -and $registered.protocol -and $registered.tasks -eq 1){'pass'}else{'fail'}) 'Inspected the packaged helper registration and its actual interactive-user scheduled task.' $registered
        $marker='Step acceptance notification'
        [void](Invoke-PackagedHelper 'show' @{title=$marker;body='Fictional acceptance fixture only';kind='task';token='acceptance:notification';tag='acceptanceprobe'})
        Result 'notification_show_api' 'pass' 'Packaged OS notification call returned successfully; visibility is checked separately.'
        Start-Sleep -Seconds 1
        $toast=Get-ToastEvidence 'acceptanceprobe' $marker $interactive
        Result 'notification_history' $(if($toast.historyContainsTag){'pass'}elseif($toast.historyError){'unsupported'}else{'fail'}) 'Queried only this isolated Step AppUserModelID in Windows notification history.' $toast
        if (!$toast.visibleTextObserved -and $interactive) {
            # Windows 11 uses Win+N; Windows 10/Server 2022 uses Win+A. Neither is evidence by itself.
            $centerKey=if([Environment]::OSVersion.Version.Build -ge 22000){0x4E}else{0x41}
            [StepAcceptanceNative]::Chord([ushort[]]@(0x5B,$centerKey));Start-Sleep -Milliseconds 700
            $toast=Get-ToastEvidence 'acceptanceprobe' $marker $true
            [StepAcceptanceNative]::Chord([ushort[]]@(0x1B))
        }
        Result 'notification_visible' $(if($toast.visibleTextObserved){'pass'}else{'unsupported'}) 'Pass requires the fictional title in a non-offscreen OS UI Automation element; API/history alone cannot pass.' $toast
        Stop-Step $uiProcess;$uiProcess=$null
        $event.exitEvidence=$script:lastExitEvidence
        if ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() -ge $event.atMillis -or (Is-ReminderHandled $event)) {throw 'Reminder was due or already handled before Step fully exited; after-exit delivery cannot be claimed.'}
        $delivery=Wait-Reminder $event 95
        Result 'scheduled_reminder_processing' $(if($delivery.handled){'pass'}else{'fail'}) 'Closed Step before the deadline, waited for its real OS time trigger, and checked the persisted handled token. No manual task Run or delivery command was issued.' $delivery
        $deliveryStatus=if(!$delivery.handled){'fail'}elseif($delivery.toast.historyContainsTag){'pass'}elseif($delivery.toast.historyError){'unsupported'}else{'fail'}
        Result 'scheduled_delivery' $deliveryStatus 'After-exit notification delivery additionally requires its real token-derived tag in OS notification history; visible display is a separate check.' $delivery
    } catch {Result 'notification_and_schedule' 'fail' (Error-Detail $_)}
    finally {Stop-Step $uiProcess;$uiProcess=$null}

    # LAST interactive operation. All earlier evidence is saved before requesting a lock.
    if ($AttemptLock -and $interactive -and $disposable -and $helperReady) {
        $observer=$null
        try {
            $event=Prepare-Reminder 35
            Stop-Step $uiProcess;$uiProcess=$null
            $event.exitEvidence=$script:lastExitEvidence
            Save-Report
            $observer=New-Object StepAcceptanceNative+LockObserver
            if (!$observer.Registered) {throw 'Could not subscribe to WTS session-lock events.'}
            $requested=[StepAcceptanceNative]::LockWorkStation()
            $locked=$observer.WaitForLock(8000)
            Result 'workstation_locked' $(if($locked){'pass'}else{'unsupported'}) 'Lock request is asynchronous; pass requires WTS_SESSION_LOCK for this process session. No unlock is attempted.' @{requestAccepted=$requested;wtsLockObserved=$locked}
            $lockedBeforeDeadline=$locked -and [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() -lt $event.atMillis -and !(Is-ReminderHandled $event)
            $delivery=Wait-Reminder $event 65
            $delivery.lockedBeforeDeadline=$lockedBeforeDeadline
            Result 'locked_reminder_processing' $(if(!$lockedBeforeDeadline){'unsupported'}elseif($delivery.handled){'pass'}else{'fail'}) 'Checked the persisted handled token after an actual time trigger while WTS reported this session locked.' $delivery
            $deliveryStatus=if(!$lockedBeforeDeadline){'unsupported'}elseif(!$delivery.handled){'fail'}elseif($delivery.toast.historyContainsTag){'pass'}elseif($delivery.toast.historyError){'unsupported'}else{'fail'}
            Result 'lock_screen_delivery' $deliveryStatus 'Requires both processing while locked and the matching tag in OS notification history; this does not prove lock-screen visual display.' $delivery
            Result 'lock_screen_visible' 'unsupported' 'Secure lock-screen pixels and user perception cannot be verified from this process; no unlock or credential access attempted.'
        } catch {Result 'lock_screen_delivery' 'fail' (Error-Detail $_)}
        finally {if($null -ne $observer){$observer.Dispose()}}
    } else {Result 'lock_screen_delivery' 'unsupported' 'Requires -AttemptLock, an interactive desktop, notification setup and a disposable GitHub-hosted VM. Locking is deliberately the final stage.'}
} catch {Result 'harness' 'fail' (Error-Detail $_)}
finally {
    foreach ($p in $processes) {try {if (!$p.HasExited) {Stop-Step $p}} catch {}}
    # A task-triggered process was not started by Start-Step. Stop only the same packaged
    # executable whose command explicitly points at this newly-created fictional profile.
    if ($disposable) {
        try {
            foreach ($running in @(Get-CimInstance Win32_Process -Filter "Name='Step.exe'" -OperationTimeoutSec 3 | Where-Object {$_.ExecutablePath -eq $Executable -and $_.CommandLine -and $_.CommandLine.Contains($DataDirectory)})) {
                $owned=Get-Process -Id $running.ProcessId -ErrorAction SilentlyContinue
                if ($null -ne $owned) {Stop-Step $owned}
            }
        } catch {}
    }
    # Only registrations keyed by this fresh profile are removed. Preserve all fictional data.
    if (Test-Path -LiteralPath $helper) {
        try {
            [void](Invoke-PackagedHelper 'configure' @{notifications=$false;scheduled=$false})
            try {[Windows.UI.Notifications.ToastNotificationManager]::History.Clear($appId)} catch {}
            $cleaned=Invoke-PackagedHelper 'inspect' @{} | ConvertFrom-Json
            Result 'cleanup' $(if(!$cleaned.shortcut -and !$cleaned.protocol -and !$cleaned.taskFolder){'pass'}else{'fail'}) 'Removed only isolated Step notification registrations and scheduled-task folder.' $cleaned
        } catch {Result 'cleanup' 'fail' (Error-Detail $_)}
    }
    foreach ($required in @('window_open','quick_capture_hotkey','unicode_edit_save','microsoft_pinyin','actual_window_dpi','multi_monitor_scaling','notification_registration','notification_show_api','notification_history','notification_visible','scheduled_delivery','lock_screen_delivery','lock_screen_visible')) {
        if (!$checks.Contains($required)) {Result $required 'unsupported' 'Not reached because an earlier prerequisite/check failed; see other checks and probe JSON.'}
    }
    Save-Report
}
if (@($checks.Values | Where-Object {$_.status -eq 'fail'}).Count -gt 0) {exit 1}
exit 0
