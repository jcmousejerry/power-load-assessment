$ErrorActionPreference = "Stop"

$script:ProjectRoot = Split-Path -Parent $PSScriptRoot
$script:ScriptsDirectory = $PSScriptRoot
$script:RuntimeDirectory = Join-Path $script:ProjectRoot "runtime"
$script:LogDirectory = Join-Path $script:RuntimeDirectory "logs"
$script:PidDirectory = Join-Path $script:RuntimeDirectory "pids"

function Initialize-RuntimeDirectories {
    New-Item -ItemType Directory -Force -Path $script:RuntimeDirectory | Out-Null
    New-Item -ItemType Directory -Force -Path $script:LogDirectory | Out-Null
    New-Item -ItemType Directory -Force -Path $script:PidDirectory | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $script:RuntimeDirectory "tmp") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $script:RuntimeDirectory "zookeeper-data") | Out-Null
}

function Test-ListeningPort {
    param(
        [Parameter(Mandatory = $true)]
        [int]$Port
    )

    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Test-ManagedProcess {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $recordFile = Join-Path $script:PidDirectory "$Name.json"
    if (-not (Test-Path -LiteralPath $recordFile)) {
        return $false
    }
    $record = Get-Content -LiteralPath $recordFile -Raw | ConvertFrom-Json
    return $null -ne (Get-Process -Id $record.processId -ErrorAction SilentlyContinue)
}

function Stop-ManagedProcess {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $recordFile = Join-Path $script:PidDirectory "$Name.json"
    if (-not (Test-Path -LiteralPath $recordFile)) {
        return
    }
    $record = Get-Content -LiteralPath $recordFile -Raw | ConvertFrom-Json
    if ($null -ne $record.viewerProcessIds) {
        foreach ($viewerProcessId in @($record.viewerProcessIds)) {
            $viewerProcess = Get-Process -Id $viewerProcessId -ErrorAction SilentlyContinue
            if ($null -ne $viewerProcess) {
                Stop-Process -Id $viewerProcessId -Force
            }
        }
    }
    $process = Get-Process -Id $record.processId -ErrorAction SilentlyContinue
    if ($null -ne $process) {
        Stop-Process -Id $record.processId -Force
        Write-Host "$Name 已停止。"
    }
    Remove-Item -LiteralPath $recordFile -Force
}

function Get-ServiceLogTail {
    param(
        [Parameter(Mandatory = $true)]
        [string]$LogFile,

        [int]$LineCount = 30
    )

    if (-not (Test-Path -LiteralPath $LogFile)) {
        return "日志文件尚未生成：$LogFile"
    }

    return (Get-Content -LiteralPath $LogFile -Tail $LineCount) -join [Environment]::NewLine
}

function Save-ManagedProcess {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [int]$Port,

        [int]$LauncherProcessId = 0
    )

    $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop |
        Select-Object -First 1
    $record = [ordered]@{
        name = $Name
        port = $Port
        processId = $connection.OwningProcess
        runnerProcessId = $LauncherProcessId
        viewerProcessIds = @()
        startedAt = (Get-Date).ToString("s")
    }
    $record | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $script:PidDirectory "$Name.json")
}

function Start-ManagedListener {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [int]$Port,

        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [string[]]$ArgumentList = @(),

        [Parameter(Mandatory = $true)]
        [string]$WorkingDirectory,

        [int]$TimeoutSeconds = 60,

        [int]$RetryCount = 0,

        [int]$RetryDelaySeconds = 5
    )

    if (Test-ListeningPort -Port $Port) {
        Write-Host "$Name 已经在运行，端口为 $Port。"
        Show-ManagedServiceLog -Name $Name
        return
    }

    Initialize-RuntimeDirectories
    $logFile = Join-Path $script:LogDirectory "$Name.log"
    $exitFile = Join-Path $script:RuntimeDirectory "$Name.exit"
    $runnerScript = Join-Path $script:ScriptsDirectory "Run-Service.ps1"
    $argumentsJson = ConvertTo-Json -InputObject @($ArgumentList) -Compress
    $argumentsBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($argumentsJson))

    for ($attempt = 0; $attempt -le $RetryCount; $attempt++) {
        Set-Content -LiteralPath $logFile -Value "" -Encoding UTF8
        Remove-Item -LiteralPath $exitFile -Force -ErrorAction SilentlyContinue

        $launcher = Start-Process `
            -FilePath "powershell.exe" `
            -ArgumentList @(
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-File", "`"$runnerScript`"",
                "-ServiceName", "`"$Name`"",
                "-Executable", "`"$FilePath`"",
                "-WorkingDirectory", "`"$WorkingDirectory`"",
                "-ArgumentsBase64", $argumentsBase64,
                "-LogFile", "`"$logFile`"",
                "-ExitFile", "`"$exitFile`""
            ) `
            -WorkingDirectory $WorkingDirectory `
            -PassThru

        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        while ((Get-Date) -lt $deadline) {
            if (Test-ListeningPort -Port $Port) {
                Save-ManagedProcess `
                    -Name $Name `
                    -Port $Port `
                    -LauncherProcessId $launcher.Id
                Write-Host "$Name 启动成功，端口为 $Port。日志窗口已打开。"
                return
            }

            if (Test-Path -LiteralPath $exitFile) {
                break
            }
            Start-Sleep -Milliseconds 500
        }

        $logTail = Get-ServiceLogTail -LogFile $logFile
        if ($attempt -lt $RetryCount) {
            Write-Host "$Name 第 $($attempt + 1) 次启动失败。关键日志如下：" -ForegroundColor Yellow
            Write-Host $logTail
            Write-Host "$RetryDelaySeconds 秒后自动重试 $Name。" -ForegroundColor Yellow
            Stop-Process -Id $launcher.Id -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds $RetryDelaySeconds
            continue
        }

        throw "$Name 启动失败，端口 $Port 没有监听。`n日志文件：$logFile`n末尾日志：`n$logTail"
    }
}

function Show-ManagedServiceLog {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    Initialize-RuntimeDirectories
    $combinedLog = Join-Path $script:LogDirectory "$Name.log"
    $oldOutputLog = Join-Path $script:LogDirectory "$Name.out.log"
    $logFile = if (Test-Path -LiteralPath $combinedLog) {
        $combinedLog
    }
    elseif (Test-Path -LiteralPath $oldOutputLog) {
        $oldOutputLog
    }
    else {
        return
    }

    $recordFile = Join-Path $script:PidDirectory "$Name.json"
    if (Test-Path -LiteralPath $recordFile) {
        $record = Get-Content -LiteralPath $recordFile -Raw | ConvertFrom-Json
        $existingWindowProcessIds = @(
            $record.runnerProcessId
            $record.launcherProcessId
            $record.viewerProcessIds
        ) | Where-Object { $null -ne $_ -and $_ -gt 0 }
        $hasOpenLogWindow = $false
        foreach ($windowProcessId in $existingWindowProcessIds) {
            if ($null -ne (Get-Process -Id $windowProcessId -ErrorAction SilentlyContinue)) {
                $hasOpenLogWindow = $true
                break
            }
        }
        if ($hasOpenLogWindow) {
            Write-Host "$Name 的日志窗口已经打开。"
            return
        }
    }

    $watchScript = Join-Path $script:ScriptsDirectory "Watch-Log.ps1"
    $viewer = Start-Process `
        -FilePath "powershell.exe" `
        -ArgumentList @(
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", "`"$watchScript`"",
            "-ServiceName", "`"$Name`"",
            "-LogFile", "`"$logFile`""
        ) `
        -PassThru

    if (Test-Path -LiteralPath $recordFile) {
        $record = Get-Content -LiteralPath $recordFile -Raw | ConvertFrom-Json
        $viewerProcessIds = @($record.viewerProcessIds) + $viewer.Id
        $record | Add-Member `
            -NotePropertyName viewerProcessIds `
            -NotePropertyValue $viewerProcessIds `
            -Force
        $record | ConvertTo-Json | Set-Content -Encoding UTF8 $recordFile
    }
}

function Show-StandaloneLog {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    Initialize-RuntimeDirectories
    $logFile = Join-Path $script:LogDirectory "$Name.log"
    if (-not (Test-Path -LiteralPath $logFile)) {
        Set-Content -LiteralPath $logFile -Value "" -Encoding UTF8
    }
    $recordFile = Join-Path $script:PidDirectory "log-viewer-$Name.json"
    if (Test-Path -LiteralPath $recordFile) {
        $record = Get-Content -LiteralPath $recordFile -Raw | ConvertFrom-Json
        if ($null -ne (Get-Process -Id $record.processId -ErrorAction SilentlyContinue)) {
            Write-Host "$Name 的日志窗口已经打开。"
            return
        }
    }

    $watchScript = Join-Path $script:ScriptsDirectory "Watch-Log.ps1"
    $viewer = Start-Process `
        -FilePath "powershell.exe" `
        -ArgumentList @(
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", "`"$watchScript`"",
            "-ServiceName", "`"$Name`"",
            "-LogFile", "`"$logFile`""
        ) `
        -PassThru
    [ordered]@{
        name = $Name
        processId = $viewer.Id
        startedAt = (Get-Date).ToString("s")
    } | ConvertTo-Json | Set-Content -Encoding UTF8 $recordFile
}

function Stop-StandaloneLog {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $recordFile = Join-Path $script:PidDirectory "log-viewer-$Name.json"
    if (-not (Test-Path -LiteralPath $recordFile)) {
        return
    }
    $record = Get-Content -LiteralPath $recordFile -Raw | ConvertFrom-Json
    Stop-Process -Id $record.processId -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $recordFile -Force
}

function Stop-ManagedListener {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $recordFile = Join-Path $script:PidDirectory "$Name.json"
    if (-not (Test-Path -LiteralPath $recordFile)) {
        return
    }

    $record = Get-Content -LiteralPath $recordFile -Raw | ConvertFrom-Json
    $connection = Get-NetTCPConnection -LocalPort $record.port -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.OwningProcess -eq $record.processId } |
        Select-Object -First 1

    if ($null -ne $connection) {
        Stop-Process -Id $record.processId -Force
        Write-Host "$Name 已停止。"
    }
    else {
        Write-Host "$Name 的原进程已经不存在，不会结束其他进程。"
    }

    $windowProcessIds = @(
        $record.runnerProcessId
        $record.launcherProcessId
        $record.viewerProcessIds
    ) | Where-Object { $null -ne $_ -and $_ -gt 0 } | Select-Object -Unique

    foreach ($windowProcessId in $windowProcessIds) {
        Stop-Process -Id $windowProcessId -Force -ErrorAction SilentlyContinue
    }

    Remove-Item -LiteralPath $recordFile -Force
}
