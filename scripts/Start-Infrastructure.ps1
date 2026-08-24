$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Common.ps1")

$kafkaHome = "D:\kafka_2.13-3.7.0"
$minioHome = "D:\java_learning\minio"
$zookeeperScript = Join-Path $kafkaHome "bin\windows\zookeeper-server-start.bat"
$kafkaScript = Join-Path $kafkaHome "bin\windows\kafka-server-start.bat"
$zookeeperConfig = Join-Path $script:ProjectRoot "config\kafka\zookeeper.properties"
$kafkaConfig = Join-Path $kafkaHome "config\server.properties"
$minioExecutable = Join-Path $minioHome "minio.exe"
$minioData = Join-Path $minioHome "data"

foreach ($requiredPath in @(
    $zookeeperScript,
    $kafkaScript,
    $zookeeperConfig,
    $kafkaConfig,
    $minioExecutable,
    $minioData
)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "缺少基础服务文件：$requiredPath"
    }
}

Initialize-RuntimeDirectories
$nativeLogRoot = Join-Path $script:LogDirectory "kafka-native"
$zookeeperNativeLog = Join-Path $nativeLogRoot "zookeeper"
$kafkaNativeLog = Join-Path $nativeLogRoot "kafka"
New-Item -ItemType Directory -Force -Path $zookeeperNativeLog | Out-Null
New-Item -ItemType Directory -Force -Path $kafkaNativeLog | Out-Null
$originalLogDirectory = $env:LOG_DIR

try {
    $zookeeperWasRunning = Test-ListeningPort -Port 2181
    $env:LOG_DIR = $zookeeperNativeLog
    $zookeeperCommand = "$zookeeperScript $zookeeperConfig"
    Start-ManagedListener `
        -Name "zookeeper" `
        -Port 2181 `
        -FilePath $env:ComSpec `
        -ArgumentList @("/c", $zookeeperCommand) `
        -WorkingDirectory $kafkaHome `
        -TimeoutSeconds 60

    if (-not $zookeeperWasRunning -and -not (Test-ListeningPort -Port 9092)) {
        Write-Host "等待 20 秒，让 ZooKeeper 清理上一次 Kafka 的登记信息。"
        Start-Sleep -Seconds 20
    }

    $env:LOG_DIR = $kafkaNativeLog
    $kafkaCommand = "$kafkaScript $kafkaConfig"
    Start-ManagedListener `
        -Name "kafka" `
        -Port 9092 `
        -FilePath $env:ComSpec `
        -ArgumentList @("/c", $kafkaCommand) `
        -WorkingDirectory $kafkaHome `
        -TimeoutSeconds 90 `
        -RetryCount 1 `
        -RetryDelaySeconds 20
}
finally {
    $env:LOG_DIR = $originalLogDirectory
}

$env:MINIO_ROOT_USER = "minioadmin"
$env:MINIO_ROOT_PASSWORD = "minioadmin"
Start-ManagedListener `
    -Name "minio" `
    -Port 9000 `
    -FilePath $minioExecutable `
    -ArgumentList @("server", $minioData, "--address", "127.0.0.1:9000", "--console-address", "127.0.0.1:9001") `
    -WorkingDirectory $minioHome `
    -TimeoutSeconds 60

Write-Host "Kafka、ZooKeeper 和 MinIO 已准备完成。"
