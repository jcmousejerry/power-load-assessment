$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Common.ps1")

Initialize-RuntimeDirectories
if (Test-ManagedProcess -Name "grid-flink-job") {
    Write-Host "Flink实时变压器预警作业已经在运行。"
    return
}

$javaExecutable = "D:\ONLY_ENGLISH_DIR\install\jdk-8\bin\java.exe"
$flinkJar = Join-Path $script:ProjectRoot "grid-flink-job\target\grid-flink-job-1.0.0-SNAPSHOT-all.jar"
foreach ($requiredPath in @($javaExecutable, $flinkJar)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Flink实时作业缺少文件：$requiredPath"
    }
}

$outputLog = Join-Path $script:LogDirectory "grid-flink-job.log"
$errorLog = Join-Path $script:LogDirectory "grid-flink-job.error.log"
Set-Content -LiteralPath $outputLog -Value "" -Encoding UTF8
Set-Content -LiteralPath $errorLog -Value "" -Encoding UTF8
$process = Start-Process `
    -FilePath $javaExecutable `
    -ArgumentList @(
        "-Xms256m",
        "-Xmx768m",
        "-jar", "`"$flinkJar`"",
        "--bootstrap", "127.0.0.1:9092",
        "--window-seconds", "10",
        "--required-windows", "3",
        "--repeat-alert-windows", "6"
    ) `
    -WorkingDirectory $script:ProjectRoot `
    -RedirectStandardOutput $outputLog `
    -RedirectStandardError $errorLog `
    -WindowStyle Hidden `
    -PassThru

Start-Sleep -Seconds 8
if ($process.HasExited) {
    $errorTail = if (Test-Path -LiteralPath $errorLog) { Get-Content -LiteralPath $errorLog -Tail 50 }
    throw "Flink实时预警作业启动失败。`n$errorTail"
}
$record = [ordered]@{
    name = "grid-flink-job"
    processId = $process.Id
    startedAt = (Get-Date).ToString("s")
}
$record | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $script:PidDirectory "grid-flink-job.json")
Write-Host "Flink实时变压器预警作业启动成功，PID=$($process.Id)。"
