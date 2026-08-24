$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Common.ps1")

Initialize-RuntimeDirectories
Stop-StandaloneLog -Name "grid-spark-scheduler"
Stop-ManagedProcess -Name "grid-flink-job"
foreach ($serviceName in @(
    "react-frontend",
    "loadflex-server",
    "loadflex-consumer",
    "algorithm-python"
)) {
    Stop-ManagedListener -Name $serviceName
}

Write-Host "本项目的前端、Java、Python 和 Flink 应用已经停止。基础服务保持运行。"
