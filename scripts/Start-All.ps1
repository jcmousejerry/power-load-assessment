param(
    [switch]$Restart,
    [switch]$RestartApplications,
    [switch]$FullBuild
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Common.ps1")

$applicationPorts = [ordered]@{
    "Python算法服务" = 8000
    "Java主服务" = 8080
    "Java消费服务" = 8081
    "React前端" = 5173
}
$runningApplications = @(
    $applicationPorts.GetEnumerator() |
        Where-Object { Test-ListeningPort -Port $_.Value }
)

if ($Restart -and $RestartApplications) {
    throw "-Restart 和 -RestartApplications 不能同时使用。"
}

if ($runningApplications.Count -eq $applicationPorts.Count `
    -and (Test-ManagedProcess -Name "grid-flink-job") `
    -and -not $Restart `
    -and -not $RestartApplications) {
    Write-Host "LoadFlex Hub 的全部应用已经在运行，不需要重复构建和启动。"
    Write-Host "前端地址：http://127.0.0.1:5173"
    Write-Host "正在打开各个服务的日志窗口。"
    & (Join-Path $PSScriptRoot "Show-All-Logs.ps1")
    Write-Host "如需完整重启，请执行：.\scripts\Start-All.ps1 -Restart"
    return
}

if ($Restart) {
    Write-Host "正在完整重启本项目管理的全部服务。"
    & (Join-Path $PSScriptRoot "Stop-Project.ps1")
    Start-Sleep -Seconds 2
}
elseif ($RestartApplications) {
    Write-Host "正在快速重启 Java、Python 和 React 应用，Kafka、ZooKeeper 和 MinIO 保持运行。"
    & (Join-Path $PSScriptRoot "Stop-Applications.ps1")
    Start-Sleep -Seconds 1
}
elseif ($runningApplications.Count -gt 0) {
    Write-Host "检测到部分应用正在运行，将先停止本项目应用，再重新构建和启动。"
    & (Join-Path $PSScriptRoot "Stop-Applications.ps1")
    Start-Sleep -Seconds 1

    $occupiedPorts = @(
        $applicationPorts.GetEnumerator() |
            Where-Object { Test-ListeningPort -Port $_.Value }
    )
}

$occupiedPorts = @(
    $applicationPorts.GetEnumerator() |
        Where-Object { Test-ListeningPort -Port $_.Value }
)
if ($occupiedPorts.Count -gt 0) {
    $portText = ($occupiedPorts | ForEach-Object { "$($_.Key)端口$($_.Value)" }) -join "、"
    throw "以下端口仍被非本项目脚本管理的进程占用：$portText。请先关闭对应程序。"
}

if ($RestartApplications) {
    Write-Host "快速应用重启将复用现有数据库、Kafka、ZooKeeper 和 MinIO。"
}
else {
    & (Join-Path $PSScriptRoot "Initialize-Database.ps1")
    & (Join-Path $PSScriptRoot "Start-Infrastructure.ps1")
    & (Join-Path $PSScriptRoot "Create-KafkaTopics.ps1")
}
if ($FullBuild) {
    & (Join-Path $PSScriptRoot "Build-Project.ps1") -Full
}
else {
    & (Join-Path $PSScriptRoot "Build-Project.ps1")
}
& (Join-Path $PSScriptRoot "Prepare-GridAnalytics.ps1")
& (Join-Path $PSScriptRoot "Start-Applications.ps1")

Write-Host "LoadFlex Hub 已经完成初始化、构建和启动。"
