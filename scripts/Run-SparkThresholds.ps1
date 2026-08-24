$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Common.ps1")

$javaHome = "D:\ONLY_ENGLISH_DIR\install\jdk-8"
$hadoopHome = "D:\ONLY_ENGLISH_DIR\install\hadoop-3.1.0"
$sparkHome = "D:\ONLY_ENGLISH_DIR\install\spark-2.4.7-bin-hadoop2.7"
$sparkSubmit = Join-Path $sparkHome "bin\spark-submit.cmd"
$sparkJar = Join-Path $script:ProjectRoot "grid-spark-job\target\grid-spark-job-1.0.0-SNAPSHOT.jar"
$sparkLocalDirectory = Join-Path $script:RuntimeDirectory "grid\spark-local"
$sparkLog4jConfiguration = Join-Path $script:ProjectRoot "config\spark\log4j.properties"
$sparkSchedulerLog = Join-Path $script:LogDirectory "grid-spark-scheduler.log"
$sparkSchedulerOldLog = Join-Path $script:LogDirectory "grid-spark-scheduler.log.1"
Initialize-RuntimeDirectories
$rotateSparkLog = $false
if (Test-Path -LiteralPath $sparkSchedulerLog) {
    $sparkLogInfo = Get-Item -LiteralPath $sparkSchedulerLog
    $sparkLogBytes = [System.IO.File]::ReadAllBytes($sparkSchedulerLog)
    $rotateSparkLog = $sparkLogInfo.Length -ge 10MB `
        -or [Array]::IndexOf($sparkLogBytes, [byte]0) -ge 0
}
if ($rotateSparkLog) {
    Remove-Item -LiteralPath $sparkSchedulerOldLog -Force -ErrorAction SilentlyContinue
    Move-Item -LiteralPath $sparkSchedulerLog -Destination $sparkSchedulerOldLog
}
$clickHouseJdbcUrl = $env:CLICKHOUSE_JDBC_URL
if ([string]::IsNullOrWhiteSpace($clickHouseJdbcUrl)) {
    $clickHouseJdbcUrl = "jdbc:clickhouse://192.168.167.134:8123/loadflex_grid"
}
$kafkaBootstrapServers = if ([string]::IsNullOrWhiteSpace($env:KAFKA_BOOTSTRAP_SERVERS)) {
    "127.0.0.1:9092"
} else {
    $env:KAFKA_BOOTSTRAP_SERVERS
}
$thresholdTopic = if ([string]::IsNullOrWhiteSpace($env:GRID_THRESHOLD_TOPIC)) {
    "grid-risk-threshold"
} else {
    $env:GRID_THRESHOLD_TOPIC
}

foreach ($requiredPath in @(
    $javaHome,
    $hadoopHome,
    $sparkSubmit,
    $sparkJar,
    $sparkLog4jConfiguration
)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Spark阈值任务缺少文件或目录：$requiredPath"
    }
}
if (-not (Test-ListeningPort -Port 9092)) {
    throw "Kafka端口9092不可用，无法发布Spark阈值。"
}

$oldJavaHome = $env:JAVA_HOME
$oldHadoopHome = $env:HADOOP_HOME
$oldSparkHome = $env:SPARK_HOME
$oldSparkLocalDirectories = $env:SPARK_LOCAL_DIRS
$oldPath = $env:Path
$oldClickHouseUsername = $env:CLICKHOUSE_USERNAME
$oldClickHousePassword = $env:CLICKHOUSE_PASSWORD
try {
    $runtimeRoot = [System.IO.Path]::GetFullPath($script:RuntimeDirectory) + `
        [System.IO.Path]::DirectorySeparatorChar
    $resolvedSparkLocalDirectory = [System.IO.Path]::GetFullPath($sparkLocalDirectory)
    if (-not $resolvedSparkLocalDirectory.StartsWith(
        $runtimeRoot,
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
        throw "Spark临时目录必须位于项目runtime目录内：$resolvedSparkLocalDirectory"
    }
    if (Test-Path -LiteralPath $sparkLocalDirectory) {
        Remove-Item -LiteralPath $sparkLocalDirectory -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $sparkLocalDirectory | Out-Null

    $env:JAVA_HOME = $javaHome
    $env:HADOOP_HOME = $hadoopHome
    $env:SPARK_HOME = $sparkHome
    $env:SPARK_LOCAL_DIRS = $sparkLocalDirectory
    $env:Path = "$javaHome\bin;$hadoopHome\bin;$oldPath"
    if ([string]::IsNullOrWhiteSpace($env:CLICKHOUSE_USERNAME)) { $env:CLICKHOUSE_USERNAME = "loadflex" }
    if ([string]::IsNullOrWhiteSpace($env:CLICKHOUSE_PASSWORD)) { $env:CLICKHOUSE_PASSWORD = "123456" }
    $modelVersion = "spark-p95-" + (Get-Date -Format "yyyyMMddHHmmssfff")
    $log4jUri = ([System.Uri]$sparkLog4jConfiguration).AbsoluteUri
    Add-Content -LiteralPath $sparkSchedulerLog -Encoding UTF8 `
        -Value "`n===== $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') Spark阈值计算开始，模型版本=$modelVersion ====="
    # Spark将正常运行日志写到stderr。先由cmd合并原生输出，避免PowerShell把INFO日志包装成错误。
    $sparkCommand = "$sparkSubmit --class com.loadflex.grid.spark.HistoricalThresholdJob " +
        "--master local[2] --conf spark.ui.enabled=false --conf spark.driver.host=127.0.0.1 " +
        "--conf `"spark.driver.extraJavaOptions=-Dfile.encoding=UTF-8 -Dlog4j.configuration=$log4jUri`" " +
        "$sparkJar $clickHouseJdbcUrl $kafkaBootstrapServers $thresholdTopic $modelVersion 2>&1"
    & $env:ComSpec /d /c $sparkCommand | ForEach-Object {
        Write-Host $_
        Add-Content -LiteralPath $sparkSchedulerLog -Encoding UTF8 -Value $_
    }
    $sparkExitCode = $LASTEXITCODE
    if ($sparkExitCode -ne 0) {
        Add-Content -LiteralPath $sparkSchedulerLog -Encoding UTF8 `
            -Value "===== $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') Spark阈值计算失败，退出代码=$sparkExitCode ====="
        throw "Spark历史阈值任务失败，退出代码：$sparkExitCode"
    }
    Add-Content -LiteralPath $sparkSchedulerLog -Encoding UTF8 `
        -Value "===== $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') Spark阈值计算成功，模型版本=$modelVersion ====="
}
finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:HADOOP_HOME = $oldHadoopHome
    $env:SPARK_HOME = $oldSparkHome
    $env:SPARK_LOCAL_DIRS = $oldSparkLocalDirectories
    $env:Path = $oldPath
    $env:CLICKHOUSE_USERNAME = $oldClickHouseUsername
    $env:CLICKHOUSE_PASSWORD = $oldClickHousePassword
    if (Test-Path -LiteralPath $sparkLocalDirectory) {
        Remove-Item -LiteralPath $sparkLocalDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
}
Write-Host "Spark已完成阈值计算、ClickHouse持久化和Kafka发布，模型版本：$modelVersion"
