import { api } from './client'
import type {
  AnalysisResult,
  AnalysisPipeline,
  AnalysisTask,
  ApiResponse,
  Dataset,
  GridRiskAlert,
  GridTransformer,
  GridTransformerMetric,
  GridTransformerMetricHistory,
  GridTelemetrySnapshot,
  PlanningTopology,
  MaintenancePlan,
  MaintenancePlanDetail,
  TransferScenario,
  TransferOperationStep,
  SimulationTask,
  SimulationPoint,
  AgentSession,
  AgentRun,
  AgentApproval,
  ControlSession,
  LoginResult,
  TaskEvent,
} from '../types'

export async function login(username: string, password: string) {
  const response = await api.post<ApiResponse<LoginResult>>('/auth/login', { username, password })
  return response.data.data
}

export async function listDatasets() {
  const response = await api.get<ApiResponse<Dataset[]>>('/datasets')
  return response.data.data
}

export async function uploadDataset(name: string, file: File) {
  const formData = new FormData()
  formData.append('name', name)
  formData.append('file', file)
  const response = await api.post<ApiResponse<Dataset>>('/datasets/upload', formData)
  return response.data.data
}

export async function saveDatasetMapping(id: number, mapping: object) {
  const response = await api.put<ApiResponse<Dataset>>(`/datasets/${id}/mapping`, {
    mappingJson: JSON.stringify(mapping),
  })
  return response.data.data
}

export async function deleteDataset(id: number) {
  await api.delete(`/datasets/${id}`)
}

export async function listPipelines(datasetId?: number) {
  const response = await api.get<ApiResponse<AnalysisPipeline[]>>('/pipelines', {
    params: { datasetId },
  })
  return response.data.data
}

export async function listTasks(filters?: { datasetId?: number; pipelineId?: number }) {
  const response = await api.get<ApiResponse<AnalysisTask[]>>('/tasks', { params: filters })
  return response.data.data
}

export async function getTaskEvents(id: number) {
  const response = await api.get<ApiResponse<TaskEvent[]>>(`/tasks/${id}/events`)
  return response.data.data
}

export async function createTask(payload: {
  taskType: string
  taskName: string
  datasetId: number
  parameters: Record<string, unknown>
}) {
  const response = await api.post<ApiResponse<AnalysisTask>>('/tasks', payload, {
    headers: { 'Idempotency-Key': crypto.randomUUID() },
  })
  return response.data.data
}

export async function createPipeline(payload: {
  datasetId: number
  pipelineName?: string
  clusterCount: number
  forecastSteps: number
  baselineType: string
  maximumAdjustableKw?: number
}) {
  const response = await api.post<ApiResponse<AnalysisTask[]>>('/tasks/pipeline', payload)
  return response.data.data
}

export async function cancelTask(id: number) {
  const response = await api.post<ApiResponse<AnalysisTask>>(`/tasks/${id}/cancel`)
  return response.data.data
}

export async function retryTask(id: number) {
  const response = await api.post<ApiResponse<AnalysisTask>>(`/tasks/${id}/retry`)
  return response.data.data
}

export async function listResults(filters?: { datasetId?: number; pipelineId?: number }) {
  const response = await api.get<ApiResponse<AnalysisResult[]>>('/results', { params: filters })
  return response.data.data
}

export async function getResult(id: number) {
  const response = await api.get<ApiResponse<AnalysisResult>>(`/results/${id}`)
  return response.data.data
}

export async function listGridTransformers() {
  const response = await api.get<ApiResponse<GridTransformer[]>>('/grid/transformers')
  return response.data.data
}

export async function listMonitoredGridTransformers() {
  const response = await api.get<ApiResponse<GridTransformer[]>>('/grid/monitoring/transformers')
  return response.data.data
}

export async function startGridTransformerMonitoring(transformerCode: string) {
  const response = await api.post<ApiResponse<GridTransformer>>(`/grid/monitoring/transformers/${transformerCode}`)
  return response.data.data
}

export async function stopGridTransformerMonitoring(transformerCode: string) {
  const response = await api.delete<ApiResponse<GridTransformer>>(`/grid/monitoring/transformers/${transformerCode}`)
  return response.data.data
}

export async function listGridMetrics() {
  const response = await api.get<ApiResponse<GridTransformerMetric[]>>('/grid/metrics')
  return response.data.data
}

export async function listLatestGridTelemetry() {
  const response = await api.get<ApiResponse<GridTelemetrySnapshot[]>>('/grid/telemetry/latest')
  return response.data.data
}

export async function listGridMetricHistory(transformerCode: string, minutes = 60) {
  const response = await api.get<ApiResponse<GridTransformerMetricHistory[]>>(
    `/grid/metrics/${transformerCode}/history`,
    { params: { minutes } },
  )
  return response.data.data
}

export async function listGridAlerts(filters?: { transformerCode?: string; alertType?: string; limit?: number }) {
  const response = await api.get<ApiResponse<GridRiskAlert[]>>('/grid/alerts', { params: filters })
  return response.data.data
}

export async function getPlanningTopology() {
  const response = await api.get<ApiResponse<PlanningTopology>>('/grid/planning/topology')
  return response.data.data
}

export async function listMaintenancePlans() {
  const response = await api.get<ApiResponse<MaintenancePlan[]>>('/grid/planning/plans')
  return response.data.data
}

export async function getMaintenancePlan(id: number) {
  const response = await api.get<ApiResponse<MaintenancePlanDetail>>(`/grid/planning/plans/${id}`)
  return response.data.data
}

export async function deleteMaintenancePlan(id: number) {
  await api.delete(`/grid/planning/plans/${id}`)
}

export async function createMaintenancePlan(payload: {
  planName: string
  reason: string
  outageDeviceType: string
  outageDeviceCode: string
  startTime: string
  endTime: string
  safetyLimit: number
  loadProfileType: string
  historicalDate?: string
  responsiblePerson?: string
}) {
  const response = await api.post<ApiResponse<MaintenancePlanDetail>>('/grid/planning/plans', payload)
  return response.data.data
}

export async function updateMaintenancePlan(id: number, payload: {
  planName: string
  reason: string
  startTime: string
  endTime: string
  safetyLimit: number
  loadProfileType: string
  historicalDate?: string
  responsiblePerson?: string
}) {
  const response = await api.put<ApiResponse<MaintenancePlanDetail>>(`/grid/planning/plans/${id}`, payload)
  return response.data.data
}

export async function generateTransferCandidates(planId: number) {
  const response = await api.post<ApiResponse<TransferScenario[]>>(`/grid/planning/plans/${planId}/candidates`)
  return response.data.data
}

export async function cloneTransferScenario(scenarioId: number) {
  const response = await api.post<ApiResponse<TransferScenario>>(`/grid/planning/scenarios/${scenarioId}/clone`)
  return response.data.data
}

export async function saveTransferSteps(scenarioId: number, steps: TransferOperationStep[]) {
  const response = await api.put<ApiResponse<TransferScenario>>(`/grid/planning/scenarios/${scenarioId}/steps`, {
    steps: steps.map(({ stepType, deviceCode, instruction, expectedState }) => ({ stepType, deviceCode, instruction, expectedState })),
  })
  return response.data.data
}

export async function startTransferSimulation(scenarioId: number) {
  const response = await api.post<ApiResponse<SimulationTask>>(`/grid/planning/scenarios/${scenarioId}/simulate`)
  return response.data.data
}

export async function getTransferSimulation(taskId: number) {
  const response = await api.get<ApiResponse<SimulationTask>>(`/grid/planning/simulations/${taskId}`)
  return response.data.data
}

export async function getTransferSimulationPoints(taskId: number) {
  const response = await api.get<ApiResponse<SimulationPoint[]>>(`/grid/planning/simulations/${taskId}/points`)
  return response.data.data
}

export async function cancelTransferSimulation(taskId: number) {
  const response = await api.post<ApiResponse<SimulationTask>>(`/grid/planning/simulations/${taskId}/cancel`)
  return response.data.data
}

export async function submitMaintenancePlan(planId: number, scenarioId: number, comment?: string) {
  const response = await api.post<ApiResponse<MaintenancePlanDetail>>(`/grid/planning/plans/${planId}/submit`, { scenarioId, comment })
  return response.data.data
}

export async function reviewMaintenancePlan(planId: number, decision: 'APPROVE' | 'REJECT', comment?: string) {
  const response = await api.post<ApiResponse<MaintenancePlanDetail>>(`/grid/planning/plans/${planId}/review`, { decision, comment })
  return response.data.data
}

export async function archiveMaintenancePlan(planId: number) {
  const response = await api.post<ApiResponse<MaintenancePlanDetail>>(`/grid/planning/plans/${planId}/archive`)
  return response.data.data
}

export async function createControlSession(scenarioId: number, sessionName?: string) {
  const response = await api.post<ApiResponse<ControlSession>>(`/grid/planning/scenarios/${scenarioId}/control-sessions`, { sessionName })
  return response.data.data
}

export async function listControlSessions(planId: number) {
  const response = await api.get<ApiResponse<ControlSession[]>>(`/grid/planning/plans/${planId}/control-sessions`)
  return response.data.data
}

export async function getControlSession(sessionId: number) {
  const response = await api.get<ApiResponse<ControlSession>>(`/grid/planning/control-sessions/${sessionId}`)
  return response.data.data
}

export async function executeControlAction(sessionId: number, actionType: string, idempotencyKey: string) {
  const response = await api.post<ApiResponse<ControlSession>>(`/grid/planning/control-sessions/${sessionId}/actions`, {
    actionType, idempotencyKey,
  })
  return response.data.data
}

export async function rollbackControlSession(sessionId: number, idempotencyKey: string) {
  const response = await api.post<ApiResponse<ControlSession>>(`/grid/planning/control-sessions/${sessionId}/rollback`, { idempotencyKey })
  return response.data.data
}

export async function listAgentSessions() {
  const response = await api.get<ApiResponse<AgentSession[]>>('/grid-planning-agent/sessions')
  return response.data.data
}

export async function createAgentSession(title?: string) {
  const response = await api.post<ApiResponse<AgentSession>>('/grid-planning-agent/sessions', { title })
  return response.data.data
}

export async function getAgentSession(id: number) {
  const response = await api.get<ApiResponse<AgentSession>>(`/grid-planning-agent/sessions/${id}`)
  return response.data.data
}

export async function deleteAgentSession(id: number) {
  await api.delete(`/grid-planning-agent/sessions/${id}`)
}

export async function sendAgentMessage(sessionId: number, content: string) {
  const response = await api.post<ApiResponse<AgentRun>>(`/grid-planning-agent/sessions/${sessionId}/messages`, { content })
  return response.data.data
}

export async function getAgentRun(runId: number) {
  const response = await api.get<ApiResponse<AgentRun>>(`/grid-planning-agent/runs/${runId}`)
  return response.data.data
}

export async function cancelAgentRun(runId: number) {
  const response = await api.post<ApiResponse<AgentRun>>(`/grid-planning-agent/runs/${runId}/cancel`)
  return response.data.data
}

export async function decideAgentApproval(approvalId: number, approve: boolean) {
  const response = await api.post<ApiResponse<AgentApproval>>(
    `/grid-planning-agent/approvals/${approvalId}/${approve ? 'approve' : 'reject'}`,
  )
  return response.data.data
}
