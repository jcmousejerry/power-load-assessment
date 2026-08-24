$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Common.ps1")

foreach ($port in @(3306, 6379, 9092, 9000, 8000, 8080, 8081, 5173)) {
    if (-not (Test-ListeningPort -Port $port)) {
        throw "端口 $port 当前不可用，请先运行 Start-All.ps1。"
    }
}

Initialize-RuntimeDirectories
$demoFile = Join-Path $script:RuntimeDirectory "e2e-demo-load.csv"
& "D:\anaconda\envs\self_env_2\python.exe" `
    (Join-Path $script:ProjectRoot "sample-data\generate_demo_data.py") `
    --output $demoFile `
    --days 30
if ($LASTEXITCODE -ne 0) {
    throw "演示数据生成失败。"
}

$loginBody = @{
    username = "admin"
    password = "123456"
} | ConvertTo-Json
$login = Invoke-RestMethod `
    -Uri "http://127.0.0.1:8080/api/auth/login" `
    -Method Post `
    -ContentType "application/json" `
    -Body $loginBody
$token = $login.data.token
$headers = @{ Authorization = "Bearer $token" }
Write-Host "[通过] 管理员登录"

$datasetName = "E2E-" + (Get-Date -Format "yyyyMMddHHmmss")
$uploadJson = & curl.exe `
    -s `
    -X POST `
    "http://127.0.0.1:8080/api/datasets/upload" `
    -H "Authorization: Bearer $token" `
    -F "name=$datasetName" `
    -F "file=@$demoFile;type=text/csv"
$upload = $uploadJson | ConvertFrom-Json
if (-not $upload.success) {
    throw "数据集上传失败：$($upload.message)"
}
$datasetId = $upload.data.id
Write-Host "[通过] 数据集上传，datasetId=$datasetId"

$mapping = @{
    userColumn = "user_id"
    timeColumn = "timestamp"
    valueColumn = "load_kw"
    unit = "kW"
    intervalMinutes = 15
} | ConvertTo-Json -Compress
$mappingBody = @{ mappingJson = $mapping } | ConvertTo-Json
Invoke-RestMethod `
    -Uri "http://127.0.0.1:8080/api/datasets/$datasetId/mapping" `
    -Method Put `
    -Headers $headers `
    -ContentType "application/json" `
    -Body $mappingBody | Out-Null
Write-Host "[通过] 字段映射保存"

$pipelineBody = @{
    datasetId = [long]$datasetId
    pipelineName = "E2E流水线"
    clusterCount = 3
    forecastSteps = 96
    baselineType = "typical"
    maximumAdjustableKw = 100
} | ConvertTo-Json
$createdPipeline = Invoke-RestMethod `
    -Uri "http://127.0.0.1:8080/api/tasks/pipeline" `
    -Method Post `
    -Headers $headers `
    -ContentType "application/json; charset=utf-8" `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($pipelineBody))
$taskIds = @($createdPipeline.data | ForEach-Object { [long]$_.id })
Write-Host "[已提交] 有依赖关系的完整流水线，共 $($taskIds.Count) 个任务"

$deadline = (Get-Date).AddMinutes(5)
do {
    Start-Sleep -Seconds 2
    $taskStates = foreach ($taskId in $taskIds) {
        (Invoke-RestMethod `
            -Uri "http://127.0.0.1:8080/api/tasks/$taskId" `
            -Headers $headers).data
    }
    $unfinished = $taskStates | Where-Object {
        $_.status -notin @("SUCCEEDED", "FAILED", "CANCELLED", "REJECTED")
    }
} while ($unfinished -and (Get-Date) -lt $deadline)

$failedTasks = $taskStates | Where-Object { $_.status -ne "SUCCEEDED" }
if ($failedTasks) {
    $failedTasks | ForEach-Object {
        Write-Host "[失败] taskId=$($_.id)，type=$($_.taskType)，status=$($_.status)，error=$($_.errorMessage)"
    }
    throw "有分析任务没有成功完成。"
}
Write-Host "[通过] 数据质量、特征、聚类及各集群预测/基线/潜力任务全部按依赖顺序成功"

$results = (Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/results" -Headers $headers).data
$e2eResults = $results | Where-Object { $_.taskId -in $taskIds }
if ($e2eResults.Count -ne $taskIds.Count) {
    throw "预期得到$($taskIds.Count)条结果，实际得到$($e2eResults.Count)条。"
}
Write-Host "[通过] 流水线全部结果写入 MySQL"

$forecastResult = $e2eResults | Where-Object { $_.resultType -eq "FORECAST" } | Select-Object -First 1
Invoke-RestMethod `
    -Uri "http://127.0.0.1:8080/api/results/$($forecastResult.id)" `
    -Headers $headers | Out-Null
$cacheExists = & "D:\java_learning\Redis\redis-cli.exe" `
    EXISTS "loadflex:result:forecast:$($forecastResult.id)"
if ($cacheExists -ne "1") {
    throw "预测结果没有写入 Redis。"
}
Write-Host "[通过] 预测结果写入 Redis"

Push-Location (Join-Path $script:ProjectRoot "frontend")
try {
    & npm run test:websocket -- $token $login.data.userId $datasetId
    if ($LASTEXITCODE -ne 0) {
        throw "WebSocket通知测试失败。"
    }
}
finally {
    Pop-Location
}

Write-Host "端到端验收完成：登录、上传、映射、排队、Kafka、Python算法、结果、Redis和WebSocket全部通过。"
