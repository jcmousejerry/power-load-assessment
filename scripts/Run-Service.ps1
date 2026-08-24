param(
    [Parameter(Mandatory = $true)]
    [string]$ServiceName,

    [Parameter(Mandatory = $true)]
    [string]$Executable,

    [Parameter(Mandatory = $true)]
    [string]$WorkingDirectory,

    [Parameter(Mandatory = $true)]
    [string]$ArgumentsBase64,

    [Parameter(Mandatory = $true)]
    [string]$LogFile,

    [Parameter(Mandatory = $true)]
    [string]$ExitFile
)

$Host.UI.RawUI.WindowTitle = "LoadFlex - $ServiceName"
$argumentsJson = [Text.Encoding]::UTF8.GetString(
    [Convert]::FromBase64String($ArgumentsBase64)
)
$serviceArguments = @($argumentsJson | ConvertFrom-Json)

Set-Location -LiteralPath $WorkingDirectory
$startMessage = "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] 正在启动 $ServiceName"
$commandMessage = "执行程序：$Executable"
$utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
$logWriter = New-Object System.IO.StreamWriter($LogFile, $true, $utf8WithoutBom)
$logWriter.AutoFlush = $true

Write-Host $startMessage
Write-Host $commandMessage
$logWriter.WriteLine($startMessage)
$logWriter.WriteLine($commandMessage)
Write-Host "日志文件：$LogFile"
Write-Host "关闭整个项目时，请运行 scripts\Stop-Project.ps1。"
Write-Host ""

try {
    & $Executable @serviceArguments 2>&1 |
        ForEach-Object {
            if ($_ -is [System.Management.Automation.ErrorRecord]) {
                $line = $_.Exception.Message
            }
            else {
                $line = $_.ToString()
            }
            Write-Host $line
            $logWriter.WriteLine($line)
        }
    $exitCode = $LASTEXITCODE
}
catch {
    $errorText = $_ | Out-String
    Write-Host $errorText
    $logWriter.WriteLine($errorText)
    $exitCode = 1
}
finally {
    $logWriter.Dispose()
}

Set-Content -LiteralPath $ExitFile -Value $exitCode -Encoding UTF8
Write-Host ""
Write-Host "$ServiceName 已退出，退出码为 $exitCode。" -ForegroundColor Yellow
Write-Host "该窗口会保留，便于查看上面的错误。"
Read-Host "按 Enter 键关闭此日志窗口"
