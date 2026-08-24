param(
    [Parameter(Mandatory = $true)]
    [string]$ServiceName,

    [Parameter(Mandatory = $true)]
    [string]$LogFile
)

$Host.UI.RawUI.WindowTitle = "LoadFlex - $ServiceName 日志"
Write-Host "服务：$ServiceName"
Write-Host "日志文件：$LogFile"
Write-Host "正在持续显示新增日志。关闭此窗口不会停止服务。"
Write-Host ""

Get-Content -LiteralPath $LogFile -Tail 80 -Wait
