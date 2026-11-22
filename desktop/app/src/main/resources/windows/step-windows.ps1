param([Parameter(Mandatory=$true)][string]$InputFile)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$request = Get-Content -LiteralPath $InputFile -Raw -Encoding UTF8 | ConvertFrom-Json
$shortcut = Join-Path ([Environment]::GetFolderPath('StartMenu')) ('Programs\Step Notifications-' + $request.profile + '.lnk')
$protocol = 'HKCU:\Software\Classes\' + $request.scheme
$folderName = '\Step-' + $request.profile

function TaskFolder([bool]$create) {
    $service = New-Object -ComObject 'Schedule.Service'; $service.Connect()
    try { return @($service, $service.GetFolder($folderName)) }
    catch { if (!$create) { return $null }; return @($service, $service.GetFolder('\').CreateFolder($folderName)) }
}
function RemoveTasks {
    $pair = TaskFolder $false
    if ($pair) {
        foreach ($task in @($pair[1].GetTasks(0))) { $pair[1].DeleteTask($task.Name,0) }
        $pair[0].GetFolder('\').DeleteFolder($folderName.TrimStart('\'),0)
    }
}
function RegisterNotifications {
    $shell = New-Object -ComObject WScript.Shell
    $link = $shell.CreateShortcut($shortcut)
    $link.TargetPath = $request.executable
    $link.Arguments = '--data-dir "' + $request.home + '"'
    $link.Description = 'Step notifications'; $link.Save()
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
public static class StepShortcut {
  [StructLayout(LayoutKind.Sequential)] public struct Key { public Guid format; public uint id; }
  [StructLayout(LayoutKind.Explicit, Size=24)] public struct Value { [FieldOffset(0)] public ushort type; [FieldOffset(8)] public IntPtr text; }
  [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface Properties {
    void GetCount(out uint count); void GetAt(uint index,out Key key); void GetValue(ref Key key,out Value value);
    void SetValue(ref Key key,ref Value value); void Commit();
  }
  public static void SetAppId(string path,string id) {
    object link=Activator.CreateInstance(Type.GetTypeFromCLSID(new Guid("00021401-0000-0000-C000-000000000046")));
    var file=(IPersistFile)link; file.Load(path,2);
    var key=new Key {format=new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"),id=5};
    var value=new Value {type=31,text=Marshal.StringToCoTaskMemUni(id)};
    try { ((Properties)link).SetValue(ref key,ref value); ((Properties)link).Commit(); file.Save(path,true); }
    finally {Marshal.FreeCoTaskMem(value.text); Marshal.ReleaseComObject(link);}
  }
}
'@
    [StepShortcut]::SetAppId($shortcut,$request.appId)
    New-Item -Path $protocol -Force | Out-Null
    Set-Item -Path $protocol -Value 'URL:Step notification'
    New-ItemProperty -Path $protocol -Name 'URL Protocol' -Value '' -PropertyType String -Force | Out-Null
    $command = $protocol + '\shell\open\command'
    New-Item -Path $command -Force | Out-Null
    Set-Item -Path $command -Value ('"' + $request.executable + '" --data-dir "' + $request.home + '" --notification-activation "%1"')
}
function Esc([string]$text) { return [System.Security.SecurityElement]::Escape($text) }
switch ($request.operation) {
    'configure' {
        if ($request.notifications) { RegisterNotifications }
        else {
            if (Test-Path -LiteralPath $protocol) { Remove-Item -LiteralPath $protocol -Recurse -Force }
            if (Test-Path -LiteralPath $shortcut) { Remove-Item -LiteralPath $shortcut -Force }
        }
        if (!$request.scheduled) { RemoveTasks }
    }
    'show' {
        [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType=WindowsRuntime] | Out-Null
        [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType=WindowsRuntime] | Out-Null
        $base = $request.scheme + '://notification?token=' + [Uri]::EscapeDataString($request.token)
        $open = Esc ($base + '&action=open')
        $done = Esc ($base + '&action=complete')
        $later = Esc ($base + '&action=snooze')
        $doneTitle = if ($request.kind -in @('focus','check')) { '记录本段' } else { '完成任务' }
        $xml = '<toast activationType="protocol" launch="' + $open + '"><visual><binding template="ToastGeneric"><text>' + (Esc $request.title) + '</text><text>' + (Esc $request.body) + '</text></binding></visual><actions><action content="' + $doneTitle + '" activationType="protocol" arguments="' + $done + '"/><action content="15分钟后提醒" activationType="protocol" arguments="' + $later + '"/></actions></toast>'
        $document = New-Object Windows.Data.Xml.Dom.XmlDocument; $document.LoadXml($xml)
        $toast = [Windows.UI.Notifications.ToastNotification]::new($document)
        $toast.Tag = $request.tag; $toast.Group = $request.profile
        [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($request.appId).Show($toast)
    }
    'schedule' {
        $pair = TaskFolder $true; $service=$pair[0]; $folder=$pair[1]
        $wanted=@{}; foreach ($event in @($request.events)) { $wanted[$event.id]=$event }
        foreach ($task in @($folder.GetTasks(0))) { if (!$wanted.ContainsKey($task.Name)) {$folder.DeleteTask($task.Name,0)} }
        foreach ($event in @($request.events)) {
            if ($event.id -notmatch '^[a-f0-9]{24}$' -or $event.token -notmatch '^[A-Za-z0-9:_-]{1,200}$') {throw 'Invalid reminder identity'}
            $definition=$service.NewTask(0)
            $definition.RegistrationInfo.Description='Step reminder delivery'
            $definition.Principal.LogonType=3; $definition.Principal.RunLevel=0
            $definition.Settings.Enabled=$true; $definition.Settings.Hidden=$true
            $definition.Settings.StartWhenAvailable=$true
            $definition.Settings.DisallowStartIfOnBatteries=$false; $definition.Settings.StopIfGoingOnBatteries=$false
            $definition.Settings.ExecutionTimeLimit='PT2M'; $definition.Settings.MultipleInstances=2
            $trigger=$definition.Triggers.Create(1)
            $at=[DateTimeOffset]::FromUnixTimeMilliseconds([long]$event.atMillis)
            $trigger.StartBoundary=$at.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
            # No expiry: after a long sleep/logoff, StartWhenAvailable must still wake Step to
            # discard stale events and arm the next recurrence. Step replaces this one task.
            $action=$definition.Actions.Create(0); $action.Path=$request.executable
            $action.Arguments='--data-dir "' + $request.home + '" --deliver-reminder "' + $event.token + '"'
            $folder.RegisterTaskDefinition($event.id,$definition,6,$null,$null,3) | Out-Null
        }
    }
    'inspect' {
        $pair=TaskFolder $false
        $tasks=@(); if ($pair) {$tasks=@($pair[1].GetTasks(0))}
        $valid=$true
        foreach ($task in $tasks) {
            $definition=$task.Definition
            if ($definition.Principal.LogonType -ne 3 -or $definition.Actions.Item(1).Path -ne $request.executable -or !$definition.Actions.Item(1).Arguments.Contains($request.home)) {$valid=$false}
        }
        @{shortcut=(Test-Path -LiteralPath $shortcut);protocol=(Test-Path -LiteralPath $protocol);taskFolder=($null -ne $pair);tasks=$tasks.Count;interactiveTasksValid=$valid} | ConvertTo-Json -Compress
        exit 0
    }
    default {throw 'Unknown system operation'}
}
Write-Output 'ok'
