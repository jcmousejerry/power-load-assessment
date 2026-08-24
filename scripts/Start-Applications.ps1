$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Common.ps1")

$serverJar = Join-Path $script:ProjectRoot "loadflex-server\target\loadflex-server-1.0.0-SNAPSHOT.jar"
$consumerJar = Join-Path $script:ProjectRoot "loadflex-consumer\target\loadflex-consumer-1.0.0-SNAPSHOT.jar"
$pythonDirectory = Join-Path $script:ProjectRoot "algorithm-python"
$frontendDirectory = Join-Path $script:ProjectRoot "frontend"

foreach ($port in @(3306, 6379, 9092, 9000)) {
    if (-not (Test-ListeningPort -Port $port)) {
        throw "依赖端口 $port 当前不可用，请先运行 Start-Infrastructure.ps1，并确认 MySQL 和 Redis 已启动。"
    }
}

foreach ($jar in @($serverJar, $consumerJar)) {
    if (-not (Test-Path -LiteralPath $jar)) {
        throw "没有找到 Java 构建产物：$jar。请先运行 Build-Project.ps1。"
    }
}

$env:ALGORITHM_TEMP_DIR = (Join-Path $script:RuntimeDirectory "tmp")
Start-ManagedListener `
    -Name "algorithm-python" `
    -Port 8000 `
    -FilePath "D:\anaconda\envs\self_env_2\python.exe" `
    -ArgumentList @("-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8000") `
    -WorkingDirectory $pythonDirectory `
    -TimeoutSeconds 60

$parallelServices = @(
    [pscustomobject]@{
        Name = "loadflex-consumer"
        Port = 8081
        FilePath = "java"
        Arguments = @("-jar", $consumerJar)
        WorkingDirectory = $script:ProjectRoot
        TimeoutSeconds = 180
    },
    [pscustomobject]@{
        Name = "loadflex-server"
        Port = 8080
        FilePath = "java"
        Arguments = @("-jar", $serverJar)
        WorkingDirectory = $script:ProjectRoot
        TimeoutSeconds = 180
    },
    [pscustomobject]@{
        Name = "react-frontend"
        Port = 5173
        FilePath = "npm.cmd"
        Arguments = @("run", "dev", "--", "--host", "127.0.0.1", "--port", "5173")
        WorkingDirectory = $frontendDirectory
        TimeoutSeconds = 120
    }
)

$commonScript = Join-Path $PSScriptRoot "Common.ps1"
$startupJobs = @()
foreach ($service in $parallelServices) {
    $startupJobs += Start-Job -ScriptBlock {
        param($commonScriptPath, $serviceDefinition)
        . $commonScriptPath
        Start-ManagedListener `
            -Name $serviceDefinition.Name `
            -Port $serviceDefinition.Port `
            -FilePath $serviceDefinition.FilePath `
            -ArgumentList @($serviceDefinition.Arguments) `
            -WorkingDirectory $serviceDefinition.WorkingDirectory `
            -TimeoutSeconds $serviceDefinition.TimeoutSeconds
    } -ArgumentList $commonScript, $service
}

try {
    $startupJobs | Wait-Job | Out-Null
    foreach ($job in $startupJobs) {
        Receive-Job -Job $job
        if ($job.State -eq "Failed") {
            throw "应用并行启动失败：$($job.ChildJobs[0].JobStateInfo.Reason.Message)"
        }
    }
}
finally {
    $startupJobs | Remove-Job -Force -ErrorAction SilentlyContinue
}

Show-StandaloneLog -Name "grid-spark-scheduler"

Write-Host "项目应用已全部启动。"
Write-Host "前端地址：http://127.0.0.1:5173"
Write-Host "默认账号：admin"
Write-Host "默认密码：123456"
