param(
    [string]$Endpoint = "http://192.168.167.134:8123",
    [string]$Username = "loadflex",
    [string]$Password = "123456"
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Common.ps1")

$schemaFile = Join-Path $script:ProjectRoot "database\clickhouse-schema.sql"
if (-not (Test-Path -LiteralPath $schemaFile)) {
    throw "没有找到ClickHouse建表脚本：$schemaFile"
}

$queryUri = "$($Endpoint.TrimEnd('/'))/?user=$([uri]::EscapeDataString($Username))&password=$([uri]::EscapeDataString($Password))"
function Invoke-ClickHouseQuery {
    param([Parameter(Mandatory = $true)][string]$Query)
    return Invoke-RestMethod -Uri $queryUri -Method Post -ContentType "text/plain; charset=utf-8" -Body $Query
}

try {
    $version = (Invoke-ClickHouseQuery -Query "SELECT version() FORMAT TSV").Trim()
}
catch {
    throw "无法连接ClickHouse $Endpoint：$($_.Exception.Message)"
}

$schema = Get-Content -LiteralPath $schemaFile -Raw -Encoding UTF8
$schemaStatements = @($schema -split ";" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
foreach ($schemaStatement in $schemaStatements) {
    Invoke-ClickHouseQuery -Query $schemaStatement | Out-Null
}

$rowCount = [long]"$(Invoke-ClickHouseQuery -Query "SELECT count() FROM loadflex_grid.transformer_history FORMAT TSV")"
if ($rowCount -eq 0) {
    $seedQuery = @"
INSERT INTO loadflex_grid.transformer_history (event_time, transformer_id, meter_id, load_kw)
WITH
    intDiv(number, 48) AS hour_index,
    intDiv(number % 48, 4) + 1 AS device_index,
    number % 4 + 1 AS meter_index,
    toStartOfHour(now()) - INTERVAL 35 DAY + toIntervalHour(hour_index) AS sample_time,
    multiIf(
        device_index = 1, 'T001', device_index = 2, 'T002', device_index = 3, 'T003',
        device_index = 4, 'T004', device_index = 5, 'T005', device_index = 6, 'T006',
        device_index = 7, 'T007', device_index = 8, 'T008', device_index = 9, 'T009',
        device_index = 10, 'T010', device_index = 11, 'T011', 'T012'
    ) AS transformer_code,
    multiIf(
        device_index = 1, 1000., device_index = 2, 800., device_index = 3, 1200.,
        device_index = 4, 280., device_index = 5, 680., device_index = 6, 430.,
        device_index = 7, 220., device_index = 8, 360., device_index = 9, 540.,
        device_index = 10, 850., device_index = 11, 280., 440.
    ) AS base_load
SELECT
    sample_time,
    transformer_code,
    concat(transformer_code, '-M', toString(meter_index)),
    round(base_load
        * (0.78 + 0.16 * sin(2 * pi() * (toHour(sample_time) - 8) / 24))
        * if(toDayOfWeek(sample_time) IN (6, 7), 0.9, 1.0)
        * (1 + 0.035 * sin(number * 0.71 + meter_index)) / 4, 3)
FROM numbers(40320)
"@
    Invoke-ClickHouseQuery -Query $seedQuery | Out-Null
    $rowCount = [long]"$(Invoke-ClickHouseQuery -Query "SELECT count() FROM loadflex_grid.transformer_history FORMAT TSV")"
    Write-Host "ClickHouse历史遥测已初始化，共${rowCount}条。"
}
else {
    Write-Host "ClickHouse历史遥测已存在，复用${rowCount}条数据。"
}
Write-Host "ClickHouse $version 连接和表结构检查通过。"
