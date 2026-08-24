param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [switch]$SkipAgent
)

$ErrorActionPreference = "Stop"

function Login([string]$Username) {
    $response = Invoke-RestMethod `
        -Uri "$BaseUrl/api/auth/login" `
        -Method Post `
        -ContentType "application/json" `
        -Body (@{ username = $Username; password = "123456" } | ConvertTo-Json)
    return @{ Authorization = "Bearer $($response.data.token)" }
}

function Wait-Simulation([long]$TaskId, $Headers) {
    for ($index = 0; $index -lt 120; $index++) {
        $task = (Invoke-RestMethod -Uri "$BaseUrl/api/grid/planning/simulations/$TaskId" -Headers $Headers).data
        if ($task.status -notin @("QUEUED", "PENDING", "RUNNING", "CANCEL_REQUESTED")) {
            return $task
        }
        Start-Sleep -Milliseconds 500
    }
    throw "仿真任务 $TaskId 等待超时"
}

function Wait-AgentRun([long]$RunId, $Headers) {
    for ($index = 0; $index -lt 90; $index++) {
        $run = (Invoke-RestMethod -Uri "$BaseUrl/api/grid-planning-agent/runs/$RunId" -Headers $Headers).data
        if ($run.status -notin @("QUEUED", "RUNNING")) {
            return $run
        }
        Start-Sleep -Seconds 2
    }
    throw "Agent运行 $RunId 等待超时"
}

$analystHeaders = Login "analyst01"
$adminHeaders = Login "admin"
$topology = (Invoke-RestMethod -Uri "$BaseUrl/api/grid/planning/topology" -Headers $analystHeaders).data
if ($topology.feeders.Count -lt 4 -or $topology.switches.Count -lt 8 -or $topology.transformers.Count -lt 12) {
    throw "拓扑档案数量不完整"
}

$suffix = Get-Date -Format "yyyyMMdd-HHmmss"
$uniqueMinute = [int](Get-Date -Format "HHmmss") % 600
$start = (Get-Date).Date.AddDays(30).AddHours(8).AddMinutes($uniqueMinute)
$end = $start.AddHours(1)
$planBody = @{
    planName = "E2E planning test-$suffix"
    reason = "Verify planning, simulation, submission and review"
    outageDeviceType = "TRANSFORMER"
    outageDeviceCode = "T012"
    startTime = $start.ToString("yyyy-MM-ddTHH:mm:ss")
    endTime = $end.ToString("yyyy-MM-ddTHH:mm:ss")
    safetyLimit = 0.98
    loadProfileType = "P90"
    responsiblePerson = "E2E tester"
} | ConvertTo-Json
$plan = (Invoke-RestMethod `
    -Uri "$BaseUrl/api/grid/planning/plans" `
    -Method Post `
    -Headers $analystHeaders `
    -ContentType "application/json" `
    -Body $planBody).data
$planId = [long]$plan.id
$candidates = (Invoke-RestMethod `
    -Uri "$BaseUrl/api/grid/planning/plans/$planId/candidates" `
    -Method Post `
    -Headers $analystHeaders).data
if ($candidates.Count -lt 2) {
    throw "没有生成足够的相邻馈线候选方案"
}

foreach ($candidate in $candidates) {
    $created = (Invoke-RestMethod `
        -Uri "$BaseUrl/api/grid/planning/scenarios/$($candidate.id)/simulate" `
        -Method Post `
        -Headers $analystHeaders).data
    $finished = Wait-Simulation ([long]$created.id) $analystHeaders
    if ($finished.status -ne "SUCCEEDED") {
        throw "候选方案 $($candidate.id) 仿真失败：$($finished.errorMessage)"
    }
    $points = (Invoke-RestMethod `
        -Uri "$BaseUrl/api/grid/planning/simulations/$($created.id)/points" `
        -Headers $analystHeaders).data
    if ($points.Count -eq 0) {
        throw "仿真任务 $($created.id) 未持久化时间片"
    }
}

$detail = (Invoke-RestMethod -Uri "$BaseUrl/api/grid/planning/plans/$planId" -Headers $analystHeaders).data
$selected = $detail.scenarios | Where-Object { $_.summary.feasible } |
    Sort-Object { $_.summary.maximumLoadRate } | Select-Object -First 1
if ($null -eq $selected) {
    throw "自动验收没有找到可提交的方案"
}

$control = (Invoke-RestMethod `
    -Uri "$BaseUrl/api/grid/planning/scenarios/$($selected.id)/control-sessions" `
    -Method Post `
    -Headers $analystHeaders `
    -ContentType "application/json" `
    -Body (@{ sessionName = "E2E manual control-$suffix" } | ConvertTo-Json)).data
foreach ($action in @("CONFIRM", "OPEN_SOURCE", "CLOSE_TIE", "TRANSFER", "VERIFY")) {
    $control = (Invoke-RestMethod `
        -Uri "$BaseUrl/api/grid/planning/control-sessions/$($control.id)/actions" `
        -Method Post `
        -Headers $analystHeaders `
        -ContentType "application/json" `
        -Body (@{ actionType = $action; idempotencyKey = [guid]::NewGuid().ToString() } | ConvertTo-Json)).data
}
if ($control.status -ne "COMPLETED" -or -not $control.verified -or $control.supplyState -ne "BACKUP" -or $control.actions.Count -ne 5) {
    throw "手动仿真调控未按五步完成"
}

$submitted = (Invoke-RestMethod `
    -Uri "$BaseUrl/api/grid/planning/plans/$planId/submit" `
    -Method Post `
    -Headers $analystHeaders `
    -ContentType "application/json" `
    -Body (@{ scenarioId = $selected.id; comment = "E2E submit" } | ConvertTo-Json)).data
if ($submitted.status -ne "UNDER_REVIEW") {
    throw "计划提交状态异常"
}
$reviewed = (Invoke-RestMethod `
    -Uri "$BaseUrl/api/grid/planning/plans/$planId/review" `
    -Method Post `
    -Headers $adminHeaders `
    -ContentType "application/json" `
    -Body (@{ decision = "APPROVE"; comment = "E2E review approved" } | ConvertTo-Json)).data
if ($reviewed.status -ne "APPROVED") {
    throw "计划审核状态异常"
}

$result = [ordered]@{
    topology = "PASS"
    planId = $planId
    scenarios = $candidates.Count
    selectedScenarioId = $selected.id
    selectedMaximumLoadPercent = $selected.summary.maximumLoadPercent
    controlSessionId = $control.id
    controlStatus = $control.status
    reviewStatus = $reviewed.status
}

if (-not $SkipAgent) {
    $agentStart = $start.AddDays(1)
    $agentEnd = $agentStart.AddHours(1)
    $agentPrompt = "Create a maintenance transfer plan for T012 from $($agentStart.ToString('yyyy-MM-dd HH:mm')) to $($agentEnd.ToString('HH:mm')), with a 98% safety limit. Prepare a draft for approval."
    $session = (Invoke-RestMethod `
        -Uri "$BaseUrl/api/grid-planning-agent/sessions" `
        -Method Post `
        -Headers $analystHeaders `
        -ContentType "application/json" `
        -Body (@{ title = "E2E Agent-$suffix" } | ConvertTo-Json)).data
    $message = (Invoke-RestMethod `
        -Uri "$BaseUrl/api/grid-planning-agent/sessions/$($session.id)/messages" `
        -Method Post `
        -Headers $analystHeaders `
        -ContentType "application/json" `
        -Body (@{ content = $agentPrompt } | ConvertTo-Json)).data
    $run = Wait-AgentRun ([long]$message.id) $analystHeaders
    $pending = $run.approvals | Where-Object { $_.status -eq "PENDING" } | Select-Object -First 1
    if ($run.status -ne "WAITING_APPROVAL" -or $null -eq $pending) {
        throw "Agent未生成待审批写操作"
    }
    $approval = (Invoke-RestMethod `
        -Uri "$BaseUrl/api/grid-planning-agent/approvals/$($pending.id)/approve" `
        -Method Post `
        -Headers $analystHeaders).data
    if ($approval.status -ne "EXECUTING") {
        throw "Agent审批没有进入后台执行状态"
    }
    $completedRun = Wait-AgentRun ([long]$message.id) $analystHeaders
    $sessionDetail = (Invoke-RestMethod `
        -Uri "$BaseUrl/api/grid-planning-agent/sessions/$($session.id)" `
        -Headers $analystHeaders).data
    $finalApproval = $completedRun.approvals | Where-Object { $_.id -eq $pending.id } | Select-Object -First 1
    $draft = $sessionDetail.draftJson | ConvertFrom-Json
    $agentControl = $null
    if ($null -ne $draft.execution.controlSessionId) {
        $agentControl = (Invoke-RestMethod `
            -Uri "$BaseUrl/api/grid/planning/control-sessions/$($draft.execution.controlSessionId)" `
            -Headers $analystHeaders).data
    }
    if ($completedRun.status -ne "COMPLETED" -or $finalApproval.status -ne "APPROVED" -or
        $null -eq $sessionDetail.linkedPlanId -or $null -eq $agentControl -or
        $agentControl.status -ne "COMPLETED" -or -not $agentControl.verified -or
        $agentControl.actions.Count -ne 5) {
        throw "Agent审批后未自主完成正式仿真、五步仿真调控与结果核验"
    }
    $result.agentSessionId = $session.id
    $result.agentRunStatus = "COMPLETED"
    $result.agentLinkedPlanId = $sessionDetail.linkedPlanId
    $result.agentControlSessionId = $draft.execution.controlSessionId
    $result.agentControlStatus = $agentControl.status
    $result.agentControlActionCount = $agentControl.actions.Count
    $result.agentControlVerified = $agentControl.verified
}

[pscustomobject]$result | ConvertTo-Json -Depth 5
