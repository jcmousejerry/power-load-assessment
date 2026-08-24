param(
    [string]$Username = "root",
    [string]$Password = "123456",
    [string]$HostName = "127.0.0.1",
    [int]$Port = 3306
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$schemaFile = Join-Path $projectRoot "database\schema.sql"
$mysqlCommand = Get-Command mysql -ErrorAction Stop

if (-not (Test-Path -LiteralPath $schemaFile)) {
    throw "没有找到数据库脚本：$schemaFile"
}

$sourcePath = $schemaFile.Replace("\", "/")
$previousMysqlPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $Password
    & $mysqlCommand.Source `
        "--host=$HostName" `
        "--port=$Port" `
        "--user=$Username" `
        "--default-character-set=utf8mb4" `
        "--execute=source $sourcePath"

    if ($LASTEXITCODE -ne 0) {
        throw "数据库初始化失败，mysql 返回代码 $LASTEXITCODE。"
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

Write-Host "数据库 loadflex_hub 和项目表初始化完成。"
