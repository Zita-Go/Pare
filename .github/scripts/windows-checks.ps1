$ErrorActionPreference = 'Stop'

function Invoke-StepCheckProcess {
    param([string]$Executable, [string[]]$StepArguments, [string]$LogName, [int]$Seconds = 120)
    $stepOut = Join-Path $env:RUNNER_TEMP "$LogName-out.log"
    $stepError = Join-Path $env:RUNNER_TEMP "$LogName-error.log"
    $stepProcess = Start-Process $Executable -ArgumentList $StepArguments -PassThru -RedirectStandardOutput $stepOut -RedirectStandardError $stepError
    if (!$stepProcess.WaitForExit($Seconds * 1000)) {
        $stepProcess.Refresh()
        Write-Output "Timed out process window: $($stepProcess.MainWindowTitle)"
        Stop-Process -Id $stepProcess.Id -Force -ErrorAction SilentlyContinue
        Get-Content $stepOut -ErrorAction SilentlyContinue
        Get-Content $stepError -ErrorAction SilentlyContinue
        throw "$LogName exceeded $Seconds seconds"
    }
    $stepProcess.Refresh()
    Get-Content $stepOut | Write-Host
    Get-Content $stepError | Write-Host
    return $stepProcess.ExitCode
}

function Get-StepInstalledExecutable {
    $stepExe = @("$env:LOCALAPPDATA\Step\Step.exe", "$env:LOCALAPPDATA\Programs\Step\Step.exe", "$env:ProgramFiles\Step\Step.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (!$stepExe) { throw 'Installed Step.exe not found' }
    return $stepExe
}
