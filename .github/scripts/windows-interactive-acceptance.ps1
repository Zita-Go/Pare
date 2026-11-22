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
$loggedProcesses = @{}
$launchNumber = 0
$helperNumber = 0
$stepTrees = @{}
$languageChanged = $false
$originalLanguageList = $null
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
function Get-ProcessExitCode($process) {
    $process.Refresh()
    if (!$process.HasExited) {throw 'Cannot read exit code before process exit.'}
    if ($loggedProcesses.ContainsKey($process.Id)) {return $loggedProcesses[$process.Id].ReadExitCode()}
    return $process.ExitCode
}
function Flush-ProcessLogs($process) {
    if ($loggedProcesses.ContainsKey($process.Id) -and $process.HasExited) {
        if (!$loggedProcesses[$process.Id].Flush(5000)) {throw 'Process exited but its output pipes did not close within five seconds.'}
    }
}
function Update-FixtureTree($process) {
    if (!$stepTrees.ContainsKey($process.Id)) {$stepTrees[$process.Id]=@{known=@{};names=@{};complete=$true;error=$null}}
    $tree=$stepTrees[$process.Id];$tree.known[$process.Id]=$process
    if (!$tree.names.ContainsKey($process.Id)) {try {$tree.names[$process.Id]=$process.ProcessName} catch {$tree.names[$process.Id]='unknown'}}
    try {
        $all=@(Get-CimInstance Win32_Process -Property ProcessId,ParentProcessId,Name,CreationDate -OperationTimeoutSec 3)
        for ($depth=0;$depth -lt 8;$depth++) {
            $changed=$false
            foreach ($item in $all) {
                $childId=[int]$item.ProcessId;$parentId=[int]$item.ParentProcessId
                if ($tree.known.ContainsKey($parentId) -and !$tree.known.ContainsKey($childId)) {
                    $parent=$tree.known[$parentId]
                    # A reused parent PID must not adopt an unrelated process.
                    if ($item.CreationDate.ToUniversalTime() -lt $parent.StartTime.ToUniversalTime()) {continue}
                    if ($parent.HasExited -and $item.CreationDate.ToUniversalTime() -gt $parent.ExitTime.ToUniversalTime()) {continue}
                    $child=Get-Process -Id $childId -ErrorAction SilentlyContinue
                    if ($null -ne $child) {
                        [void]$child.Handle
                        if ([Math]::Abs(($child.StartTime.ToUniversalTime()-$item.CreationDate.ToUniversalTime()).TotalSeconds) -lt 1) {
                            $tree.known[$childId]=$child;$tree.names[$childId]=[IO.Path]::GetFileNameWithoutExtension($item.Name);$changed=$true
                        }
                    }
                }
            }
            if (!$changed) {break}
        }
    } catch {$tree.complete=$false;$tree.error=Error-Detail $_}
    return $tree
}
function Get-StepWindows($process) {
    $tree=Update-FixtureTree $process
    foreach ($member in @($tree.known.Values)) {if (!$member.HasExited) {[StepAcceptanceNative]::Windows($member.Id)}}
}
function Restore-TestLanguages {
    if (!$languageChanged) {return}
    try {
        Set-WinUserLanguageList -LanguageList $originalLanguageList -Force -WarningAction SilentlyContinue
        $after=@(Get-WinUserLanguageList | ForEach-Object {@{language=$_.LanguageTag;inputMethodTips=@($_.InputMethodTips)}})
        $expected=@($originalLanguageList | ForEach-Object {@{language=$_.LanguageTag;inputMethodTips=@($_.InputMethodTips)}})
        $same=($after | ConvertTo-Json -Depth 5 -Compress) -eq ($expected | ConvertTo-Json -Depth 5 -Compress)
        Result 'input_languages_restored' $(if($same){'pass'}else{'fail'}) 'Restored the original current-user language list and input method tips.' @{profiles=$after}
        if ($same) {$script:languageChanged=$false}
    } catch {Result 'input_languages_restored' 'fail' (Error-Detail $_)}
}
function Write-ProcessDiagnostic($process,[string]$stage) {
    $snapshot=[ordered]@{stage=$stage;launchProcessId=$process.Id;capturedUtc=[DateTimeOffset]::UtcNow.ToString('o');relatedProcesses=@()}
    try {
        $process.Refresh();$snapshot.exited=$process.HasExited
        if ($process.HasExited) {$snapshot.exitCode=Get-ProcessExitCode $process;Flush-ProcessLogs $process}
    } catch {$snapshot.processStateError=Error-Detail $_}
    if ($loggedProcesses.ContainsKey($process.Id)) {
        $logged=$loggedProcesses[$process.Id]
        $snapshot.stdout=[IO.Path]::GetFileName($logged.StandardOutputPath)
        $snapshot.stderr=[IO.Path]::GetFileName($logged.StandardErrorPath)
    }
    # Query identities/ancestry only. Never dump unrelated command lines or environment.
    try {
        $all=@(Get-CimInstance Win32_Process -Property ProcessId,ParentProcessId,Name,SessionId -OperationTimeoutSec 3)
        $ids=New-Object 'Collections.Generic.HashSet[int]';[void]$ids.Add($process.Id)
        for ($depth=0;$depth -lt 8;$depth++) {
            $changed=$false
            foreach ($item in $all) {if ($ids.Contains([int]$item.ParentProcessId) -and $ids.Add([int]$item.ProcessId)) {$changed=$true}}
            if (!$changed) {break}
        }
        foreach ($item in @($all | Where-Object {$ids.Contains([int]$_.ProcessId)} | Select-Object -First 16)) {
            $snapshot.relatedProcesses+=@{processId=$item.ProcessId;parentId=$item.ParentProcessId;name=$item.Name;sessionId=$item.SessionId;windows=@([StepAcceptanceNative]::Windows([int]$item.ProcessId,$false))}
        }
        if (!@($snapshot.relatedProcesses | Where-Object {$_.processId -eq $process.Id}).Count) {
            $snapshot.relatedProcesses+=@{processId=$process.Id;notInLiveSnapshot=$true;windows=@([StepAcceptanceNative]::Windows($process.Id,$false))}
        }
    } catch {$snapshot.ancestryError=Error-Detail $_}
    $name='process-'+$process.Id+'-'+$stage+'.json'
    Write-JsonFile (Join-Path $OutputDirectory $name) $snapshot
    return $name
}
function Start-Step([string[]]$arguments) {
    $script:launchNumber++
    $label='step-launch-'+$script:launchNumber.ToString('00')
    $quoted = @($arguments | ForEach-Object { '"'+$_+'"' }) -join ' '
    $logged=[StepAcceptanceNative+ProcessLog]::new($Executable,$quoted,(Join-Path $OutputDirectory ($label+'.stdout.log')),(Join-Path $OutputDirectory ($label+'.stderr.log')),$false)
    $p=$logged.Process
    $loggedProcesses[$p.Id]=$logged
    $processes.Add($p)
    [void](Update-FixtureTree $p)
    [void](Write-ProcessDiagnostic $p 'started')
    return $p
}
function Stop-Step($process) {
    if ($null -eq $process) {return}
    $tree=Update-FixtureTree $process
    $closeRequested=$false;$forced=$false
    foreach ($member in @($tree.known.Values)) {
        if ($member.HasExited) {continue}
        foreach ($w in @([StepAcceptanceNative]::Windows($member.Id))) {
            if ($w.Title -eq 'Step' -and [StepAcceptanceNative]::PostMessage([IntPtr]$w.Handle,0x10,[IntPtr]::Zero,[IntPtr]::Zero)) {$closeRequested=$true}
        }
    }
    $timer=[Diagnostics.Stopwatch]::StartNew()
    do {
        $tree=Update-FixtureTree $process
        $live=@($tree.known.Values | Where-Object {!$_.HasExited})
        if (!$live.Count) {break}
        Start-Sleep -Milliseconds 300
    } while ($timer.Elapsed.TotalSeconds -lt 10)
    if ($live.Count) {
        [void](Write-ProcessDiagnostic $process 'before-forced-stop')
        foreach ($member in $live) {
            # Only descendants of this explicitly launched fixture are candidates. Unknown
            # child executables are left alone and make process-tree exit unconfirmed.
            if ($tree.names[$member.Id] -in @('Step','java','javaw','powershell')) {
                $forced=$true;try {$member.Kill();[void]$member.WaitForExit(5000)} catch {}
            }
        }
    }
    $tree=Update-FixtureTree $process
    $live=@($tree.known.Values | Where-Object {!$_.HasExited})
    $treeExited=$tree.complete -and $live.Count -eq 0
    $code=$null;if($process.HasExited){$code=Get-ProcessExitCode $process;if($treeExited){Flush-ProcessLogs $process}}
    $members=@($tree.known.Values | ForEach-Object {@{processId=$_.Id;name=$tree.names[$_.Id];exited=$_.HasExited;exitCode=$(if($_.HasExited){Get-ProcessExitCode $_}else{$null})}})
    $badAppExit=@($members | Where-Object {$_.name -in @('Step','java','javaw') -and $_.exitCode -ne 0}).Count -gt 0
    $script:lastExitEvidence=@{processId=$process.Id;confirmedExited=$process.HasExited;processTreeExited=$treeExited;treeObservationComplete=$tree.complete;treeError=$tree.error;relatedProcesses=$members;forced=$forced;normalExitObserved=($treeExited -and !$forced -and !$badAppExit -and $closeRequested -and $code -eq 0);closeRequested=$closeRequested;exitCode=$code;exitObservedUtc=[DateTimeOffset]::UtcNow.ToString('o')}
    [void](Write-ProcessDiagnostic $process 'exited')
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
    [StepAcceptanceNative]::Chord([System.UInt16[]]@(0x11,0x10,0x20))
    if (!(Wait-Until { @(Get-StepWindows $uiProcess | Where-Object {$_.Title -like 'Step*随手记*'}).Count -gt 0 } 8)) {throw 'Ctrl+Shift+Space did not reveal the Step quick-capture window.'}
    return @(Get-StepWindows $uiProcess | Where-Object {$_.Title -like 'Step*随手记*'})[0]
}
function Invoke-PackagedHelper([string]$operation,[hashtable]$payload) {
    $script:helperNumber++
    $label='helper-'+$script:helperNumber.ToString('00')+'-'+$operation
    $request=@{operation=$operation;home=$DataDirectory;executable=$Executable;profile=$profileId;appId=$appId;scheme=('step-'+$profileId)}
    foreach ($key in $payload.Keys) {$request[$key]=$payload[$key]}
    $requestPath=Join-Path $OutputDirectory ($label+'.request.json')
    Write-JsonFile $requestPath $request
    $stdout=Join-Path $OutputDirectory ($label+'.stdout.log')
    $stderr=Join-Path $OutputDirectory ($label+'.stderr.log')
    $command='-NoProfile -NonInteractive -ExecutionPolicy RemoteSigned -File "'+$helper+'" -InputFile "'+$requestPath+'"'
    $logged=[StepAcceptanceNative+ProcessLog]::new("$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe",$command,$stdout,$stderr,$true)
    $p=$logged.Process
    $loggedProcesses[$p.Id]=$logged
    $processes.Add($p)
    try {
        if (!$p.WaitForExit(35000)) {
            $p.Kill()
            if (!$p.WaitForExit(5000)) {throw ('Packaged helper remained alive after timeout; inspect '+$label+' logs.')}
            throw ('Packaged helper timed out; inspect '+$label+' logs.')
        }
        $code=Get-ProcessExitCode $p;Flush-ProcessLogs $p
        if ($code -ne 0) {throw ('Packaged notification helper exit '+$code+'; inspect '+$label+'.stderr.log')}
        return (Get-Content -LiteralPath $stdout -Raw -Encoding UTF8).Trim()
    } finally {
        # These requests contain only this fictional profile. Keep every attempt and failure log.
        [void](Write-ProcessDiagnostic $p 'helper-finished')
    }
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
    if (!$event.exitEvidence.processTreeExited) {return @{handled=$false;observationSkipped=$true;exitScope='unconfirmed-process-tree';exitEvidence=$event.exitEvidence;detail='Known fixture descendants were not all confirmed exited; no after-exit claim is possible.'}}
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
    $exitScope=if($event.exitEvidence.normalExitObserved){'normal-exit'}elseif($event.exitEvidence.forced){'forced-termination'}else{'unexpected-or-unverified-exit'}
    $evidence=@{handled=$handled;observationSkipped=$false;schedulerEvidenceLimit='Step may delete its task before polling observes LastRunTime; null is not proof of an observed Scheduler run.';exitScope=$exitScope;exitEvidence=$event.exitEvidence;taskSchedulerObservation=$taskObservation;dueUtc=[DateTimeOffset]::FromUnixTimeMilliseconds($event.atMillis).ToString('o');observedUtc=[DateTimeOffset]::UtcNow.ToString('o');scheduledAction='packaged Step --deliver-reminder';manuallyInvoked=$false}
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
    if (!$pinyinAvailable -and $probe.pinyinTipRegistered) {
        try {
            $originalLanguageList=Get-WinUserLanguageList
            $testLanguageList=Get-WinUserLanguageList
            if (!@($testLanguageList | Where-Object {$_.LanguageTag -eq 'zh-CN'}).Count) {$testLanguageList.Add('zh-CN')}
            $chinese=@($testLanguageList | Where-Object {$_.LanguageTag -eq 'zh-CN'})[0]
            if ($chinese.InputMethodTips -notcontains $pinyinTip) {$chinese.InputMethodTips.Add($pinyinTip)}
            $languageChanged=$true
            $languageWarnings=@()
            Set-WinUserLanguageList -LanguageList $testLanguageList -Force -WarningVariable languageWarnings -WarningAction SilentlyContinue
            $after=@(Get-WinUserLanguageList | ForEach-Object {@{language=$_.LanguageTag;inputMethodTips=@($_.InputMethodTips)}})
            $pinyinAvailable=@($after | Where-Object {$_.inputMethodTips -contains $pinyinTip}).Count -gt 0
            Result 'pinyin_profile_configuration' $(if($pinyinAvailable){'pass'}else{'unsupported'}) 'Appended the already-registered Microsoft Pinyin profile only on this disposable VM; original languages are retained. No package installation or login is performed.' @{before=$probe.inputLanguages;after=$after;warnings=@($languageWarnings | ForEach-Object {$_.ToString()} | Select-Object -First 3)}
        } catch {Result 'pinyin_profile_configuration' 'unsupported' (Error-Detail $_)}
    }
    $seed=Start-Step @('--self-test','--data-dir',$DataDirectory)
    if (!$seed.WaitForExit(45000)) {$seed.Kill();throw 'Packaged fixture self-test timed out.'}
    $seedCode=Get-ProcessExitCode $seed;Flush-ProcessLogs $seed
    [void](Write-ProcessDiagnostic $seed 'self-test-finished')
    if ($seedCode -ne 0 -or !(Test-Path -LiteralPath (Join-Path $DataDirectory 'smoke-result.json'))) {throw 'Packaged fixture self-test failed.'}
    [IO.File]::WriteAllText((Join-Path $DataDirectory 'window.properties'),"keepInTray=false`n",$utf8)
    if ($interactive) {
        try {
            $uiProcess=Start-Step @('--data-dir',$DataDirectory)
            if (!(Wait-Until { @(Get-StepWindows $uiProcess | Where-Object {$_.Title -eq 'Step'}).Count -gt 0 } 35)) {
                $diagnostic=Write-ProcessDiagnostic $uiProcess 'window-timeout'
                Result 'window_discovery' 'fail' 'Harness did not find the expected visible top-level Step window. Startup logs and all related process windows must distinguish product startup from discovery failure.' @{diagnostic=$diagnostic}
                throw ('No matching Step window for launch PID within 35 seconds; inspect '+$diagnostic+' and referenced stdout/stderr.')
            }
            $main=@(Get-StepWindows $uiProcess | Where-Object {$_.Title -eq 'Step'})[0]
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
            [StepAcceptanceNative]::Chord([System.UInt16[]]@(0x11,0x41))
            $savedText='验收中文：整理想法，迈出最小一步。'
            [StepAcceptanceNative]::UnicodeText($savedText)
            try {[StepAcceptanceNative]::CaptureWindow([IntPtr]$capture.Handle,(Join-Path $OutputDirectory 'step-chinese-text-native.png'))} catch {Result 'text_screenshot' 'unsupported' (Error-Detail $_)}
            [StepAcceptanceNative]::Chord([System.UInt16[]]@(0x11,0x0D))
            $saved=Wait-Until { @(Get-Inbox | Where-Object {$_.payload.content.text -eq $savedText}).Count -eq 1 } 12
            Result 'unicode_edit_save' $(if($saved){'pass'}else{'fail'}) 'KEYEVENTF_UNICODE text, native Ctrl+A replacement and Ctrl+Enter save checked against isolated persisted inbox; this is NOT an IME test.' @{exactPersistedText=$saved}
            if ($pinyinAvailable) {
                try {
                    Focus-Step $main.Handle
                    $capture=Open-Capture;Focus-Step $capture.Handle
                    $originalLayout=[StepAcceptanceNative]::WindowKeyboardLayout([IntPtr]$capture.Handle)
                    $before=@(Get-Inbox | ForEach-Object {$_.id})
                    $imeActivated=$false
                    if (![StepAcceptanceNative]::RequestChineseInput([IntPtr]$capture.Handle)) {throw 'No loaded Chinese input locale accepted the language-change request.'}
                    Start-Sleep -Milliseconds 400
                    $activation=[StepAcceptanceNative]::ActivatePinyin()
                    Start-Sleep -Milliseconds 700
                    $targetLayout=[StepAcceptanceNative]::WindowKeyboardLayout([IntPtr]$capture.Handle)
                    if ($activation -ne 0 -or ($targetLayout -band 0xffff) -ne 0x804) {throw 'Configured Microsoft Pinyin did not activate for the Step window.'}
                    $imeActivated=$true
                    Result 'pinyin_activation' 'pass' 'Registered Pinyin activated in the current session without signing in again.' @{activationHresult=$activation;targetLanguageId=($targetLayout -band 0xffff)}
                    [StepAcceptanceNative]::AsciiKeys('nihao')
                    Start-Sleep -Milliseconds 600
                    $candidate=[StepAcceptanceNative]::CandidateVisible()
                    [StepAcceptanceNative]::Chord([System.UInt16[]]@(0x20))
                    Start-Sleep -Milliseconds 300
                    [StepAcceptanceNative]::Chord([System.UInt16[]]@(0x11,0x0D))
                    $converted=Wait-Until { @(Get-Inbox | Where-Object {$before -notcontains $_.id -and $_.payload.content.text -match '[\u4e00-\u9fff]'}).Count -gt 0 } 12
                    Result 'microsoft_pinyin' $(if($converted){'pass'}else{'fail'}) 'Real Microsoft Pinyin activated; only Latin virtual keys nihao and candidate commit were sent, then persisted Chinese was checked.' @{activationHresult=$activation;targetLanguageId=($targetLayout -band 0xffff);candidateWindowObserved=$candidate;chinesePersisted=$converted}
                    Result 'ime_candidate_layout' 'unsupported' 'Candidate-window observation alone cannot validate placement, clipping or all composition interactions.' @{candidateWindowObserved=$candidate}
                } catch {Result 'microsoft_pinyin' $(if((Get-Variable imeActivated -ErrorAction SilentlyContinue) -and $imeActivated){'fail'}else{'unsupported'}) ((Error-Detail $_)+'; no login, unlock or unregistered package installation attempted.')}
                finally {if (Get-Variable originalLayout -ErrorAction SilentlyContinue) {[void][StepAcceptanceNative]::RestoreKeyboard($originalLayout)}}
            } else {Result 'microsoft_pinyin' 'unsupported' 'Microsoft Pinyin could not be made available from the existing registered/configured profiles in this session; no Unicode result counts as IME acceptance.'}
        } catch {Result 'interactive_input' 'fail' (Error-Detail $_)}
        finally {Stop-Step $uiProcess;$uiProcess=$null}
    } else {
        foreach ($name in @('window_open','quick_capture_hotkey','unicode_edit_save','microsoft_pinyin','actual_window_dpi','multi_monitor_scaling')) {Result $name 'unsupported' 'Active interactive desktop prerequisite failed; see probe JSON.'}
    }
    Restore-TestLanguages
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
            [StepAcceptanceNative]::Chord([System.UInt16[]]@(0x5B,$centerKey));Start-Sleep -Milliseconds 700
            $toast=Get-ToastEvidence 'acceptanceprobe' $marker $true
            [StepAcceptanceNative]::Chord([System.UInt16[]]@(0x1B))
        }
        Result 'notification_visible' $(if($toast.visibleTextObserved){'pass'}else{'unsupported'}) 'Pass requires the fictional title in a non-offscreen OS UI Automation element; API/history alone cannot pass.' $toast
        Stop-Step $uiProcess;$uiProcess=$null
        $event.exitEvidence=$script:lastExitEvidence
        Result 'normal_exit_before_reminder' $(if($event.exitEvidence.normalExitObserved){'pass'}elseif($event.exitEvidence.closeRequested){'fail'}else{'unsupported'}) 'Normal exit requires WM_CLOSE, zero exit code and no forced kill. A sent close request that does not finish within ten seconds fails; no available main window is unsupported.' $event.exitEvidence
        if ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() -ge $event.atMillis -or (Is-ReminderHandled $event)) {throw 'Reminder was due or already handled before Step fully exited; after-exit delivery cannot be claimed.'}
        $delivery=Wait-Reminder $event 95
        Result 'scheduled_reminder_processing' $(if($delivery.observationSkipped){'unsupported'}elseif($delivery.handled){'pass'}else{'fail'}) ('Termination scope: '+$delivery.exitScope+'. Waited for the real OS trigger after confirmed exit; checked the handled token. No manual task Run or delivery command was issued.') $delivery
        $deliveryStatus=if($delivery.observationSkipped){'unsupported'}elseif(!$delivery.handled){'fail'}elseif($delivery.toast.historyContainsTag){'pass'}elseif($delivery.toast.historyError){'unsupported'}else{'fail'}
        Result 'scheduled_delivery' $deliveryStatus ('Termination scope: '+$delivery.exitScope+'. Delivery requires its token-derived tag in OS history; this does not establish normal quit or visible display.') $delivery
    } catch {Result 'notification_and_schedule' 'fail' (Error-Detail $_)}
    finally {Stop-Step $uiProcess;$uiProcess=$null}

    # LAST interactive operation. All earlier evidence is saved before requesting a lock.
    if ($AttemptLock -and $interactive -and $disposable -and $helperReady) {
        $observer=$null
        try {
            $event=Prepare-Reminder 35
            Stop-Step $uiProcess;$uiProcess=$null
            $event.exitEvidence=$script:lastExitEvidence
            Result 'normal_exit_before_locked_reminder' $(if($event.exitEvidence.normalExitObserved){'pass'}elseif($event.exitEvidence.closeRequested){'fail'}else{'unsupported'}) 'Normal quit requires WM_CLOSE and clean exit within ten seconds; force after an attempted close fails, while unavailable close prerequisites are unsupported.' $event.exitEvidence
            Save-Report
            $observer=New-Object StepAcceptanceNative+LockObserver
            if (!$observer.Registered) {throw 'Could not subscribe to WTS session-lock events.'}
            $requested=[StepAcceptanceNative]::LockWorkStation()
            $locked=$observer.WaitForLock(8000)
            Result 'workstation_locked' $(if($locked){'pass'}else{'unsupported'}) 'Lock request is asynchronous; pass requires WTS_SESSION_LOCK for this process session. No unlock is attempted.' @{requestAccepted=$requested;wtsLockObserved=$locked}
            $lockedBeforeDeadline=$event.exitEvidence.processTreeExited -and $locked -and [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() -lt $event.atMillis -and !(Is-ReminderHandled $event)
            $delivery=Wait-Reminder $event 65
            $delivery.lockedBeforeDeadline=$lockedBeforeDeadline
            Result 'locked_reminder_processing' $(if(!$lockedBeforeDeadline){'unsupported'}elseif($delivery.handled){'pass'}else{'fail'}) ('Termination scope: '+$delivery.exitScope+'. Checked the handled token after an actual time trigger while WTS reported this session locked.') $delivery
            $deliveryStatus=if(!$lockedBeforeDeadline){'unsupported'}elseif(!$delivery.handled){'fail'}elseif($delivery.toast.historyContainsTag){'pass'}elseif($delivery.toast.historyError){'unsupported'}else{'fail'}
            Result 'lock_screen_delivery' $deliveryStatus ('Termination scope: '+$delivery.exitScope+'. Requires processing while locked and matching OS history; normal quit and lock-screen visibility are separate checks.') $delivery
            Result 'lock_screen_visible' 'unsupported' 'Secure lock-screen pixels and user perception cannot be verified from this process; no unlock or credential access attempted.'
        } catch {Result 'lock_screen_delivery' 'fail' (Error-Detail $_)}
        finally {if($null -ne $observer){$observer.Dispose()}}
    } else {Result 'lock_screen_delivery' 'unsupported' 'Requires -AttemptLock, an interactive desktop, notification setup and a disposable GitHub-hosted VM. Locking is deliberately the final stage.'}
} catch {Result 'harness' 'fail' (Error-Detail $_)}
finally {
    Restore-TestLanguages
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
    foreach ($required in @('window_open','quick_capture_hotkey','unicode_edit_save','microsoft_pinyin','actual_window_dpi','multi_monitor_scaling','notification_registration','notification_show_api','notification_history','notification_visible','scheduled_delivery','normal_exit_before_reminder','normal_exit_before_locked_reminder','lock_screen_delivery','lock_screen_visible')) {
        if (!$checks.Contains($required)) {Result $required 'unsupported' 'Not reached because an earlier prerequisite/check failed; see other checks and probe JSON.'}
    }
    Save-Report
}
if (@($checks.Values | Where-Object {$_.status -eq 'fail'}).Count -gt 0) {exit 1}
exit 0
