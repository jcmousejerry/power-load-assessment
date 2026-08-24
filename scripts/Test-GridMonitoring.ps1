param(
    [int]$TimeoutSeconds = 150
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Common.ps1")

if (-not (Test-ListeningPort -Port 8080)) {
    throw "Java主服务没有运行，请先执行 Start-All.ps1。"
}
if (-not (Test-ManagedProcess -Name "grid-flink-job")) {
    throw "Flink实时预警作业没有运行。"
}

$clickHouseQueryUri = "http://192.168.167.134:8123/?user=loadflex&password=123456"
$latestThresholdCountQuery = @"
SELECT count()
FROM loadflex_grid.transformer_risk_threshold
WHERE model_version = (
    SELECT model_version
    FROM loadflex_grid.transformer_risk_threshold
    ORDER BY calculated_at DESC
    LIMIT 1
)
FORMAT TSV
"@
$latestThresholdCount = [int](Invoke-RestMethod `
    -Uri $clickHouseQueryUri `
    -Method Post `
    -ContentType "text/plain; charset=utf-8" `
    -Body $latestThresholdCountQuery)
if ($latestThresholdCount -ne 576) {
    throw "ClickHouse中最新Spark模型应包含576条阈值，实际为$latestThresholdCount。"
}
Write-Host "[通过] Spark最新模型的576条阈值已持久化到ClickHouse"

$loginBody = @{ username = "admin"; password = "123456" } | ConvertTo-Json
$login = Invoke-RestMethod `
    -Uri "http://127.0.0.1:8080/api/auth/login" `
    -Method Post `
    -ContentType "application/json" `
    -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.data.token)" }
$testStartedAt = (Get-Date).AddSeconds(-5)
$testStartedAtClickHouse = $testStartedAt.ToString("yyyy-MM-dd HH:mm:ss")
$transformers = (Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/grid/transformers" -Headers $headers).data
if ($transformers.Count -lt 12) {
    throw "预期设备库至少存在12台演示变压器，实际为$($transformers.Count)台。"
}
$monitored = (Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/grid/monitoring/transformers" -Headers $headers).data
$originalMonitoredCodes = @($monitored | ForEach-Object { $_.transformerCode })
$available = @($transformers | Where-Object { -not $_.monitoringEnabled }) | Select-Object -First 1
if ($null -eq $available) {
    throw "设备库中没有可用于验收添加/删除的未监控设备。"
}
$testCode = $available.transformerCode
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/grid/monitoring/transformers/$testCode" -Method Post -Headers $headers | Out-Null
$afterAdd = (Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/grid/monitoring/transformers" -Headers $headers).data
if (@($afterAdd | Where-Object { $_.transformerCode -eq $testCode }).Count -ne 1) {
    throw "添加$testCode实时监控失败。"
}
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/grid/monitoring/transformers/$testCode" -Method Delete -Headers $headers | Out-Null
$afterRemove = (Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/grid/monitoring/transformers" -Headers $headers).data
if (@($afterRemove | Where-Object { $_.transformerCode -eq $testCode }).Count -ne 0) {
    throw "删除$testCode实时监控失败。"
}
Write-Host "[通过] $($transformers.Count)台设备档案已初始化，用户可添加/删除监控对象"

$demoCodes = @("T001", "T002", "T003")
$temporaryDemoCodes = @($demoCodes | Where-Object { $_ -notin $originalMonitoredCodes })
foreach ($code in $temporaryDemoCodes) {
    Invoke-RestMethod `
        -Uri "http://127.0.0.1:8080/api/grid/monitoring/transformers/$code" `
        -Method Post `
        -Headers $headers | Out-Null
}

try {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $metricsReady = $false
    $telemetryReady = $false
    $historyReady = $false
    $archiveReady = $false
    $expectedAlertsReady = $false
    while ((Get-Date) -lt $deadline) {
        $telemetry = (Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/grid/telemetry/latest" -Headers $headers).data
        $metrics = (Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/grid/metrics" -Headers $headers).data
        $metricHistory = (Invoke-RestMethod `
            -Uri "http://127.0.0.1:8080/api/grid/metrics/T001/history?limit=20" `
            -Headers $headers).data
        $alerts = (Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/grid/alerts?limit=200" -Headers $headers).data
        $archiveQuery = @"
SELECT uniqExact(tuple(transformer_id, meter_id))
FROM loadflex_grid.transformer_realtime_telemetry FINAL
WHERE event_time >= toDateTime('$testStartedAtClickHouse', 'Asia/Shanghai')
  AND transformer_id IN ('T001', 'T002', 'T003')
FORMAT TSV
"@
        $archiveDeviceMeters = [int](Invoke-RestMethod `
            -Uri $clickHouseQueryUri `
            -Method Post `
            -ContentType "text/plain; charset=utf-8" `
            -Body $archiveQuery)
        $archiveReady = $archiveDeviceMeters -eq 12
        $demoTelemetry = @($telemetry | Where-Object { $_.transformerCode -in $demoCodes })
        $demoMetrics = @($metrics | Where-Object { $_.transformerCode -in $demoCodes })
        $telemetryReady = $demoTelemetry.Count -eq 3 -and @($demoTelemetry | Where-Object {
            $null -eq $_.sampleTime -or @($_.meterLoadsKw.PSObject.Properties).Count -ne 4
        }).Count -eq 0
        $metricsReady = $demoMetrics.Count -eq 3 `
            -and @($demoMetrics | Where-Object { $null -eq $_.historicalUpperKw }).Count -eq 0
        $historyReady = $metricHistory.Count -gt 0 `
            -and @($metricHistory | Where-Object { $null -eq $_.historicalUpperKw }).Count -eq 0
        $hasT001Overload = @($alerts | Where-Object {
            $_.transformerCode -eq "T001" -and $_.alertType -eq "OVERLOAD" `
                -and ([datetime]$_.eventTime) -ge $testStartedAt
        }).Count -gt 0
        $hasT002Historical = @($alerts | Where-Object {
            $_.transformerCode -eq "T002" -and $_.alertType -eq "HISTORICAL_ANOMALY" `
                -and ([datetime]$_.eventTime) -ge $testStartedAt
        }).Count -gt 0
        $expectedAlertsReady = $hasT001Overload -and $hasT002Historical
        if ($telemetryReady -and $archiveReady -and $metricsReady -and $historyReady -and $expectedAlertsReady) {
            break
        }
        Start-Sleep -Seconds 5
    }

    if (-not $telemetryReady) {
        throw "超时：没有收到三台变压器及其四块电表的最新原始采集快照。"
    }
    Write-Host "[通过] 三台设备每台四块电表的原始采集快照均可实时查询"
    if (-not $archiveReady) {
        throw "超时：三台变压器的十二块电表原始遥测没有批量持久化到ClickHouse。"
    }
    Write-Host "[通过] Kafka原始遥测已由Java批量持久化到ClickHouse"
    if (-not $metricsReady) {
        throw "超时：没有收到包含Spark历史阈值的三台变压器Flink实时指标。"
    }
    Write-Host "[通过] Kafka遥测经Flink窗口汇总，三台设备实时指标均已入库"
    if (-not $historyReady) {
        throw "超时：Flink窗口指标没有写入趋势历史表。"
    }
    Write-Host "[通过] Flink窗口指标历史已持久化，可用于负荷趋势可视化"
    if (-not $expectedAlertsReady) {
        throw "超时：没有同时观察到T001容量过载和T002历史异常升高预警。"
    }
    Write-Host "[通过] T001容量过载和T002历史异常升高均在连续3个窗口后产生预警"

    $unexpectedT003 = @($alerts | Where-Object {
        $_.transformerCode -eq "T003" -and ([datetime]$_.eventTime) -ge $testStartedAt
    }).Count
    if ($unexpectedT003 -gt 0) {
        throw "T003应保持正常，但产生了${unexpectedT003}条预警。"
    }
    Write-Host "[通过] 正常设备T003没有误报"
}
finally {
    foreach ($code in $temporaryDemoCodes) {
        Invoke-RestMethod `
            -Uri "http://127.0.0.1:8080/api/grid/monitoring/transformers/$code" `
            -Method Delete `
            -Headers $headers | Out-Null
    }
}
Write-Host "实时预警验收完成：ClickHouse原始遥测与Spark阈值、Kafka、Flink、MySQL落库和查询API全部通过。"
