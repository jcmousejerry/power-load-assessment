$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Common.ps1")

if (-not (Test-ListeningPort -Port 9092)) {
    throw "Kafka 还没有启动，端口 9092 当前不可用。"
}

$topicCommand = "D:\kafka_2.13-3.7.0\bin\windows\kafka-topics.bat"
$configCommand = "D:\kafka_2.13-3.7.0\bin\windows\kafka-configs.bat"
$topics = @(
    [pscustomobject]@{ Name = "loadflex-task-command"; Partitions = 3; Retention = $null },
    [pscustomobject]@{ Name = "loadflex-task-notification"; Partitions = 3; Retention = $null },
    [pscustomobject]@{
        Name = "grid-telemetry-raw"
        Partitions = 3
        Retention = "cleanup.policy=delete,retention.ms=604800000,retention.bytes=536870912"
    },
    [pscustomobject]@{
        Name = "grid-risk-threshold"
        Partitions = 1
        Retention = "cleanup.policy=delete,retention.ms=604800000,retention.bytes=134217728"
    },
    [pscustomobject]@{
        Name = "grid-transformer-metric"
        Partitions = 3
        Retention = "cleanup.policy=delete,retention.ms=604800000,retention.bytes=268435456"
    },
    [pscustomobject]@{
        Name = "grid-risk-alert"
        Partitions = 3
        Retention = "cleanup.policy=delete,retention.ms=604800000,retention.bytes=67108864"
    }
)
foreach ($topic in $topics) {
    & $topicCommand `
        --bootstrap-server "127.0.0.1:9092" `
        --create `
        --if-not-exists `
        --topic $topic.Name `
        --partitions $topic.Partitions `
        --replication-factor 1

    if ($LASTEXITCODE -ne 0) {
        throw "创建 Kafka Topic $($topic.Name) 失败。"
    }

    if (-not [string]::IsNullOrWhiteSpace($topic.Retention)) {
        & $configCommand `
            --bootstrap-server "127.0.0.1:9092" `
            --entity-type topics `
            --entity-name $topic.Name `
            --alter `
            --add-config $topic.Retention
        if ($LASTEXITCODE -ne 0) {
            throw "更新 Kafka Topic $($topic.Name) 的数据保留策略失败。"
        }
    }
}

Write-Host "Kafka Topic 已准备完成。"
