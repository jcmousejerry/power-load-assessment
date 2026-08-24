param(
    [switch]$Full
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "Common.ps1")

if ((Test-ListeningPort -Port 8080) -or (Test-ListeningPort -Port 8081)) {
    throw "Java应用正在运行，JAR文件被占用。请先运行 Stop-Applications.ps1，再执行构建。"
}

function Get-LatestWriteTime {
    param([string[]]$Paths)

    $latest = [DateTime]::MinValue
    foreach ($path in $Paths) {
        if (-not (Test-Path -LiteralPath $path)) {
            continue
        }
        $item = Get-Item -LiteralPath $path
        if ($item.PSIsContainer) {
            $candidate = Get-ChildItem -LiteralPath $path -Recurse -File |
                Sort-Object LastWriteTime -Descending |
                Select-Object -First 1
            if ($null -ne $candidate -and $candidate.LastWriteTime -gt $latest) {
                $latest = $candidate.LastWriteTime
            }
        }
        elseif ($item.LastWriteTime -gt $latest) {
            $latest = $item.LastWriteTime
        }
    }
    return $latest
}

$serverJar = Join-Path $projectRoot "loadflex-server\target\loadflex-server-1.0.0-SNAPSHOT.jar"
$consumerJar = Join-Path $projectRoot "loadflex-consumer\target\loadflex-consumer-1.0.0-SNAPSHOT.jar"
$sparkJar = Join-Path $projectRoot "grid-spark-job\target\grid-spark-job-1.0.0-SNAPSHOT.jar"
$flinkJar = Join-Path $projectRoot "grid-flink-job\target\grid-flink-job-1.0.0-SNAPSHOT-all.jar"
$commonJavaInputs = @(
    (Join-Path $projectRoot "pom.xml"),
    (Join-Path $projectRoot "config\checkstyle"),
    (Join-Path $projectRoot "loadflex-common\pom.xml"),
    (Join-Path $projectRoot "loadflex-common\src")
)
$serverInputs = $commonJavaInputs + @(
    (Join-Path $projectRoot "loadflex-server\pom.xml"),
    (Join-Path $projectRoot "loadflex-server\src")
)
$consumerInputs = $commonJavaInputs + @(
    (Join-Path $projectRoot "loadflex-consumer\pom.xml"),
    (Join-Path $projectRoot "loadflex-consumer\src")
)
$sparkInputs = @(
    (Join-Path $projectRoot "grid-spark-job\pom.xml"),
    (Join-Path $projectRoot "grid-spark-job\src")
)
$flinkInputs = @(
    (Join-Path $projectRoot "grid-flink-job\pom.xml"),
    (Join-Path $projectRoot "grid-flink-job\src")
)
$javaBuildRequired = $Full `
    -or -not (Test-Path -LiteralPath $serverJar) `
    -or -not (Test-Path -LiteralPath $consumerJar) `
    -or -not (Test-Path -LiteralPath $sparkJar) `
    -or -not (Test-Path -LiteralPath $flinkJar) `
    -or (Get-Item -LiteralPath $serverJar -ErrorAction SilentlyContinue).LastWriteTime `
        -lt (Get-LatestWriteTime -Paths $serverInputs) `
    -or (Get-Item -LiteralPath $consumerJar -ErrorAction SilentlyContinue).LastWriteTime `
        -lt (Get-LatestWriteTime -Paths $consumerInputs) `
    -or (Get-Item -LiteralPath $sparkJar -ErrorAction SilentlyContinue).LastWriteTime `
        -lt (Get-LatestWriteTime -Paths $sparkInputs) `
    -or (Get-Item -LiteralPath $flinkJar -ErrorAction SilentlyContinue).LastWriteTime `
        -lt (Get-LatestWriteTime -Paths $flinkInputs)

if ($javaBuildRequired) {
    Push-Location $projectRoot
    try {
        if ($Full) {
            Write-Host "正在执行 Java 完整格式检查、测试和构建。"
            & mvn -q `
                -pl loadflex-common,loadflex-server,loadflex-consumer `
                com.diffplug.spotless:spotless-maven-plugin:2.43.0:apply
            if ($LASTEXITCODE -ne 0) {
                throw "Java 代码格式整理失败。"
            }
            & mvn -q verify
        }
        else {
            Write-Host "检测到 Java 源码变化，正在增量生成运行 JAR。"
            & mvn -q -DskipTests package
        }
        if ($LASTEXITCODE -ne 0) {
            throw "Java 项目构建失败。"
        }
    }
    finally {
        Pop-Location
    }
}
else {
    Write-Host "Java 源码没有变化，复用现有 JAR。"
}

$frontendDirectory = Join-Path $projectRoot "frontend"
Push-Location $frontendDirectory
try {
    if (-not (Test-Path "node_modules")) {
        Write-Host "首次运行，正在安装前端依赖。"
        & npm install --no-fund --no-audit
        if ($LASTEXITCODE -ne 0) {
            throw "前端依赖安装失败。"
        }
    }
    if ($Full) {
        Write-Host "正在执行 React 类型检查和生产构建。"
        & npm run build
        if ($LASTEXITCODE -ne 0) {
            throw "React 前端构建失败。"
        }
    }
    else {
        Write-Host "React 使用 Vite 开发模式，无需预先生产构建。"
    }
}
finally {
    Pop-Location
}

if ($Full) {
    Push-Location (Join-Path $projectRoot "algorithm-python")
    try {
        Write-Host "正在执行 Python 算法测试。"
        & "D:\anaconda\envs\self_env_2\python.exe" -m unittest discover -s tests -v
        if ($LASTEXITCODE -ne 0) {
            throw "Python 算法测试失败。"
        }
    }
    finally {
        Pop-Location
    }
    Write-Host "Java、Python 和 React 完整验证完成。"
}
else {
    Write-Host "快速构建准备完成。需要完整验证时执行：.\scripts\Build-Project.ps1 -Full"
}
