$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Common.ps1")

$serviceNames = @(
    "zookeeper",
    "kafka",
    "minio",
    "algorithm-python",
    "loadflex-consumer",
    "loadflex-server",
    "react-frontend",
    "grid-flink-job"
)

$openedCount = 0
foreach ($serviceName in $serviceNames) {
    $recordFile = Join-Path $script:PidDirectory "$serviceName.json"
    if (Test-Path -LiteralPath $recordFile) {
        Show-ManagedServiceLog -Name $serviceName
        $openedCount++
    }
}

Show-StandaloneLog -Name "grid-spark-scheduler"
$openedCount++

if ($openedCount -eq 0) {
    Write-Host "当前没有找到由项目脚本启动的服务。"
}
else {
    Write-Host "已为 $openedCount 个服务打开日志窗口。"
}
