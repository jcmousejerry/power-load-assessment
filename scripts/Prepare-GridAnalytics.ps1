$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Common.ps1")

Stop-ManagedProcess -Name "grid-flink-job"
& (Join-Path $PSScriptRoot "Initialize-ClickHouse.ps1")
& (Join-Path $PSScriptRoot "Run-SparkThresholds.ps1")
& (Join-Path $PSScriptRoot "Start-GridFlinkJob.ps1")
Write-Host "Spark历史阈值和Flink实时预警链路已准备完成。"
