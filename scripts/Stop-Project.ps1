$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Common.ps1")

Initialize-RuntimeDirectories
Stop-StandaloneLog -Name "grid-spark-scheduler"
Stop-ManagedProcess -Name "grid-flink-job"
$serviceNames = @(
    "react-frontend",
    "loadflex-server",
    "loadflex-consumer",
    "algorithm-python",
    "minio",
    "kafka",
    "zookeeper"
)

foreach ($serviceName in $serviceNames) {
    Stop-ManagedListener -Name $serviceName
}

Write-Host "本项目通过脚本启动的进程已经停止。MySQL 和 Redis 不受影响。"
