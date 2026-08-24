param(
    [switch]$Force,
    [string]$Username = "root",
    [string]$Password = "123456",
    [string]$HostName = "127.0.0.1",
    [int]$Port = 3306
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Common.ps1")

if (-not $Force) {
    throw "此操作会清空 loadflex_hub 的数据集、流水线、任务、结果和审计记录，并删除 MinIO loadflex bucket 中的全部对象。确认后请使用 -Force。"
}

$databaseScript = Join-Path $script:ProjectRoot "scripts\clear-project-data.sql"
$minioScript = Join-Path $script:ProjectRoot "algorithm-python\scripts\clear_project_bucket.py"
$pythonExecutable = "D:\anaconda\envs\self_env_2\python.exe"
$redisExecutable = "D:\java_learning\Redis\redis-cli.exe"

foreach ($requiredFile in @($databaseScript, $minioScript, $pythonExecutable, $redisExecutable)) {
    if (-not (Test-Path -LiteralPath $requiredFile)) {
        throw "清理所需文件不存在：$requiredFile"
    }
}

$sourcePath = $databaseScript.Replace("\", "/")
$previousMysqlPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $Password
    & mysql `
        "--host=$HostName" `
        "--port=$Port" `
        "--user=$Username" `
        "--default-character-set=utf8mb4" `
        "--execute=source $sourcePath"
    if ($LASTEXITCODE -ne 0) {
        throw "清理 loadflex_hub 失败，mysql 返回代码 $LASTEXITCODE。"
    }
}
finally {
    if ($null -eq $previousMysqlPassword) {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }
    else {
        $env:MYSQL_PWD = $previousMysqlPassword
    }
}

& $pythonExecutable $minioScript
if ($LASTEXITCODE -ne 0) {
    throw "清理 MinIO loadflex bucket 失败。"
}

$redisKeys = @(& $redisExecutable --scan --pattern "loadflex:*")
foreach ($redisKey in $redisKeys) {
    if (-not [string]::IsNullOrWhiteSpace($redisKey)) {
        & $redisExecutable DEL $redisKey | Out-Null
    }
}

Write-Host "项目历史数据已清空：保留 app_user，其余项目业务记录、loadflex MinIO 对象和 loadflex Redis 缓存已删除。"
