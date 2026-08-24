export type ApiResponse<T> = {
  success: boolean
  message: string
  data: T
}

export type LoginResult = {
  token: string
  userId: number
  username: string
  displayName: string
  roleCode: string
}

export type Dataset = {
  id: number
  ownerId: number
  name: string
  originalFilename: string
  fileSize: number
  status: string
  mappingJson?: string
  profileJson?: string
  rowCount?: number
  userCount?: number
  createdAt: string
}

export type AnalysisPipeline = {
  id: number
  userId: number
  datasetId: number
  pipelineName: string
  createdAt: string
}

export type AnalysisTask = {
  id: number
  userId: number
  datasetId: number
  pipelineId?: number
  taskType: string
  taskName: string
  status: string
  stage?: string
  progress: number
  resourcePool: string
  parametersJson?: string
  queuePosition?: number
  queuedAheadCount?: number
  estimatedWaitSeconds?: number
  estimatedStartAt?: string
  etaP50At?: string
  etaP90At?: string
  etaConfidence?: string
  errorMessage?: string
  createdAt: string
  queuedAt?: string
  startedAt?: string
  finishedAt?: string
}

export type TaskEvent = {
  id: number
  taskId: number
  attemptNo: number
  eventSeq: number
  eventType: string
  payloadJson?: string
  createdAt: string
}

export type AnalysisResult = {
  id: number
  taskId: number
  resultType: string
  summaryJson: string
  artifactObjectKey?: string
  algorithmVersion: string
  createdAt: string
}

export type GridTransformer = {
  id: number
  transformerCode: string
  transformerName: string
  areaName: string
  feederCode?: string
  ratedCapacityKw: number
  status: string
  monitoringEnabled: boolean
}

export type GridFeeder = {
  feederCode: string
  feederName: string
  ratedCapacityKw: number
  sourceName: string
  status: string
}

export type GridSwitch = {
  switchCode: string
  switchName: string
  switchType: 'BREAKER' | 'TIE'
  fromFeederCode: string
  toFeederCode?: string
  normalState: 'OPEN' | 'CLOSED'
  status: string
}

export type PlanningTopology = {
  feeders: GridFeeder[]
  switches: GridSwitch[]
  transformers: GridTransformer[]
}

export type MaintenancePlan = {
  id: number
  ownerId: number
  ownerName: string
  planName: string
  reason: string
  outageDeviceType: 'TRANSFORMER' | 'FEEDER' | 'SWITCH'
  outageDeviceCode: string
  startTime: string
  endTime: string
  safetyLimit: number
  loadProfileType: 'P50' | 'P90' | 'HISTORICAL_DAY'
  historicalDate?: string
  responsiblePerson?: string
  status: string
  selectedScenarioId?: number
  createdAt: string
  updatedAt: string
}

export type PlanningImpact = {
  sourceFeederCode: string
  impactedTransformers: GridTransformer[]
  impactedTransformerCodes: string[]
  ratedCapacityKw: number
  estimatedTransferLoadKw: number
  dataSource: string
  alternativeFeeders: Array<{
    switchCode: string
    switchName: string
    targetFeederCode: string
    targetFeederName: string
    ratedCapacityKw: number
  }>
  conflicts: Array<{
    planId: number
    planName: string
    deviceCode: string
    startTime: string
    endTime: string
    status: string
  }>
}

export type TransferOperationStep = {
  id?: number
  scenarioId?: number
  stepNo: number
  stepType: string
  deviceCode?: string
  instruction: string
  expectedState?: string
}

export type SimulationTask = {
  id: number
  scenarioId: number
  requestedBy: number
  status: string
  progress: number
  stage?: string
  dataSource?: string
  errorMessage?: string
  startedAt?: string
  finishedAt?: string
  createdAt: string
}

export type SimulationPoint = {
  id: number
  taskId: number
  timePoint: string
  entityType: 'TRANSFORMER' | 'FEEDER'
  entityCode: string
  beforeLoadKw: number
  transferLoadKw: number
  afterLoadKw: number
  capacityKw: number
  loadRate: number
  riskStatus: 'NORMAL' | 'WARNING' | 'OVERLOAD'
}

export type TransferScenarioSummary = {
  feasible: boolean
  maximumLoadRate: number
  maximumLoadPercent: number
  networkMaximumLoadRate?: number
  networkMaximumLoadPercent?: number
  violationPointCount: number
  backgroundViolationPointCount?: number
  unservedEnergyKwh: number
  operationStepCount: number
  conflictCount: number
  riskEquipment: string[]
  backgroundRiskEquipment?: string[]
  timePointCount: number
  dataSource: string
  sourceFeederCode: string
  targetFeederCode: string
  targetTransformerCode: string
  tieSwitchCode: string
}

export type TransferScenario = {
  id: number
  planId: number
  scenarioName: string
  versionNo: number
  sourceFeederCode: string
  targetFeederCode: string
  targetTransformerCode: string
  tieSwitchCode: string
  loadBlocksJson: string
  loadBlocks?: string[]
  status: string
  latestTaskId?: number
  latestTask?: SimulationTask
  summaryJson?: string
  summary?: TransferScenarioSummary
  steps: TransferOperationStep[]
  createdAt: string
  updatedAt: string
}

export type PlanReviewRecord = {
  id: number
  planId: number
  action: string
  operatorId: number
  operatorName: string
  comment?: string
  createdAt: string
}

export type MaintenancePlanDetail = MaintenancePlan & {
  scenarios: TransferScenario[]
  reviews: PlanReviewRecord[]
  impact: PlanningImpact
}

export type ControlActionDefinition = {
  actionType: 'CONFIRM' | 'OPEN_SOURCE' | 'CLOSE_TIE' | 'TRANSFER' | 'VERIFY'
  deviceCode?: string
  instruction: string
  buttonText: string
}

export type ControlActionRecord = {
  id: number
  sequenceNo: number
  actionType: string
  deviceCode?: string
  status: string
  instruction: string
  operatorName: string
  createdAt: string
}

export type ControlSession = {
  id: number
  planId: number
  scenarioId: number
  createdBy: number
  sessionName: string
  status: 'READY' | 'RUNNING' | 'COMPLETED' | 'ROLLED_BACK' | 'FAILED'
  currentStepNo: number
  sourceSwitchState: 'OPEN' | 'CLOSED'
  tieSwitchState: 'OPEN' | 'CLOSED'
  supplyState: 'NORMAL' | 'INTERRUPTED' | 'BACKUP'
  transferred: boolean
  verified: boolean
  version: number
  createdAt: string
  updatedAt: string
  completedAt?: string
  scenario?: TransferScenario
  actions?: ControlActionRecord[]
  nextAction?: ControlActionDefinition
  operationChecklist?: Array<ControlActionDefinition & { stepNo: number; status: 'COMPLETED' | 'CURRENT' | 'PENDING' }>
  effect?: {
    phase: 'BEFORE' | 'SWITCHING' | 'AFTER'
    supplyState: string
    affectedTransformer: string
    targetTransformer: string
    maximumLoadPercent?: number
    message: string
  }
  frame?: SimulationPoint[]
  simulationOnly?: boolean
}

export type AgentMessage = {
  id: number
  sessionId: number
  role: 'USER' | 'ASSISTANT' | 'SYSTEM'
  content: string
  metadataJson?: string
  createdAt: string
}

export type AgentStep = {
  id: number
  runId: number
  stepNo: number
  stepType: string
  toolName?: string
  status: string
  summary: string
  detailJson?: string
  createdAt: string
}

export type AgentApproval = {
  id: number
  runId: number
  sessionId: number
  userId: number
  actionType: string
  status: string
  previewJson: string
  payloadJson: string
  decidedAt?: string
  createdAt: string
}

export type AgentRun = {
  id: number
  sessionId: number
  userId: number
  status: string
  currentStage?: string
  errorMessage?: string
  inputTokens: number
  outputTokens: number
  startedAt: string
  finishedAt?: string
  steps?: AgentStep[]
  approvals?: AgentApproval[]
}

export type AgentSession = {
  id: number
  userId: number
  title: string
  linkedPlanId?: number
  draftJson?: string
  createdAt: string
  updatedAt: string
  messages?: AgentMessage[]
  runs?: AgentRun[]
  approvals?: AgentApproval[]
}

export type GridTransformerMetric = {
  transformerCode: string
  windowStart: string
  windowEnd: string
  currentLoadKw: number
  ratedCapacityKw: number
  loadRate: number
  historicalUpperKw?: number
  status: 'NORMAL' | 'WATCH' | 'HISTORICAL_ANOMALY' | 'OVERLOAD'
  consecutiveAbnormalWindows: number
  updatedAt: string
}

export type GridTransformerMetricHistory = GridTransformerMetric & {
  id: number
  createdAt: string
}

export type GridTelemetrySnapshot = {
  transformerCode: string
  sampleTime: string
  totalLoadKw: number
  ratedCapacityKw: number
  meterLoadsKw: Record<string, number>
}

export type GridRiskAlert = {
  id: number
  eventId: string
  transformerCode: string
  alertType: 'HISTORICAL_ANOMALY' | 'OVERLOAD'
  severity: 'MEDIUM' | 'HIGH'
  currentLoadKw: number
  ratedCapacityKw: number
  loadRate: number
  historicalUpperKw?: number
  consecutiveWindows: number
  eventTime: string
  createdAt: string
}
