import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert, Button, Card, Col, Empty, List, Popconfirm, Progress, Row, Select, Space, Statistic, Steps, Tag, Timeline, Typography, message,
} from 'antd'
import { CheckCircleOutlined, ControlOutlined, DeleteOutlined, PlayCircleOutlined, RollbackOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  createControlSession, createMaintenancePlan, deleteMaintenancePlan, executeControlAction, generateTransferCandidates, getControlSession,
  getMaintenancePlan, getPlanningTopology, getTransferSimulationPoints, listControlSessions, listGridAlerts,
  listGridMetrics, listMaintenancePlans, rollbackControlSession, startTransferSimulation,
} from '../api/services'
import GridTopologyChart from '../components/GridTopologyChart'
import PageHeader from '../components/PageHeader'
import { useAuthStore } from '../store/auth'
import type { ControlSession, MaintenancePlanDetail, SimulationPoint, TransferScenario } from '../types'

const runningStatuses = new Set(['QUEUED', 'PENDING', 'RUNNING', 'CANCEL_REQUESTED'])

export default function SimpleEmergencyPage() {
  const user = useAuthStore((state) => state.user)!
  const [searchParams] = useSearchParams()
  const [deviceCode, setDeviceCode] = useState<string | undefined>(searchParams.get('device') || undefined)
  const [duration, setDuration] = useState(60)
  const [selectedPlanId, setSelectedPlanId] = useState<number | undefined>(() => {
    const value = Number(searchParams.get('plan'))
    return Number.isFinite(value) && value > 0 ? value : undefined
  })
  const [selectedScenarioId, setSelectedScenarioId] = useState<number>()
  const [controlSessionId, setControlSessionId] = useState<number>()
  const queryClient = useQueryClient()
  const topology = useQuery({ queryKey: ['planning-topology'], queryFn: getPlanningTopology })
  const metrics = useQuery({ queryKey: ['grid-metrics'], queryFn: listGridMetrics, refetchInterval: 3000 })
  const alerts = useQuery({ queryKey: ['grid-alerts-simple'], queryFn: () => listGridAlerts({ limit: 50 }), refetchInterval: 5000 })
  const plans = useQuery({ queryKey: ['maintenance-plans'], queryFn: listMaintenancePlans })
  const detail = useQuery({
    queryKey: ['maintenance-plan', selectedPlanId],
    queryFn: () => getMaintenancePlan(selectedPlanId!),
    enabled: Boolean(selectedPlanId),
    refetchInterval: selectedPlanId ? 1000 : false,
  })
  const controlSessions = useQuery({
    queryKey: ['control-sessions', selectedPlanId], queryFn: () => listControlSessions(selectedPlanId!),
    enabled: Boolean(selectedPlanId),
  })
  const control = useQuery({
    queryKey: ['control-session', controlSessionId], queryFn: () => getControlSession(controlSessionId!),
    enabled: Boolean(controlSessionId), refetchInterval: controlSessionId ? 1500 : false,
  })

  const transformer = topology.data?.transformers.find((item) => item.transformerCode === deviceCode)
  const metric = metrics.data?.find((item) => item.transformerCode === deviceCode)
  const latestAlert = alerts.data?.find((item) => item.transformerCode === deviceCode)
  const analysis = useMutation({
    mutationFn: async () => {
      if (!deviceCode) throw new Error('请先选择一台设备')
      const start = dayjs().add(5, 'minute').second(0).millisecond(0)
      const end = start.add(duration, 'minute')
      const condition = plainStatus(metric?.status)
      const plan = await createMaintenancePlan({
        planName: `${deviceCode}异常影响分析 · ${dayjs().format('MM-DD HH:mm')}`,
        reason: `实时监控发现设备当前为“${condition}”，模拟设备暂时不可用并检查备用供电能力`,
        outageDeviceType: 'TRANSFORMER',
        outageDeviceCode: deviceCode,
        startTime: start.format('YYYY-MM-DDTHH:mm:ss'),
        endTime: end.format('YYYY-MM-DDTHH:mm:ss'),
        safetyLimit: 0.95,
        loadProfileType: 'P90',
        responsiblePerson: user.displayName,
      })
      setSelectedPlanId(plan.id)
      const candidates = await generateTransferCandidates(plan.id)
      if (!candidates.length) throw new Error('附近没有可用的备用供电连接')
      setSelectedScenarioId(candidates[0].id)
      await Promise.all(candidates.map((item) => startTransferSimulation(item.id)))
      return plan.id
    },
    onSuccess: async () => {
      message.success('影响分析已启动，系统正在自动比较备用方案')
      await queryClient.invalidateQueries({ queryKey: ['maintenance-plans'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  const scenarios = detail.data?.scenarios || []
  const stillRunning = scenarios.some((item) => item.latestTask && runningStatuses.has(item.latestTask.status))
  const finished = scenarios.filter((item) => item.summary)
  const best = useMemo(() => chooseBest(finished), [finished])
  useEffect(() => {
    if (!stillRunning && best) setSelectedScenarioId(best.id)
  }, [best, stillRunning])
  const selectedScenario = scenarios.find((item) => item.id === selectedScenarioId) || best || scenarios[0]
  const taskId = selectedScenario?.latestTaskId
  const points = useQuery({
    queryKey: ['simulation-points', taskId],
    queryFn: () => getTransferSimulationPoints(taskId!),
    enabled: Boolean(taskId) && !stillRunning,
  })
  const frame = useMemo(() => lastFrame(points.data || []), [points.data])
  const progress = scenarios.length
    ? Math.round(scenarios.reduce((sum, item) => sum + Number(item.latestTask?.progress || 0), 0) / scenarios.length)
    : 0

  const openHistory = (id: number) => {
    setSelectedPlanId(id)
    setSelectedScenarioId(undefined)
    setControlSessionId(undefined)
  }

  useEffect(() => {
    if (!controlSessionId && controlSessions.data?.length) setControlSessionId(controlSessions.data[0].id)
  }, [controlSessionId, controlSessions.data])

  const createControl = useMutation({
    mutationFn: () => createControlSession(selectedScenario!.id),
    onSuccess: async (item) => {
      setControlSessionId(item.id)
      message.success('手动仿真调控台已准备好，请按步骤操作')
      await queryClient.invalidateQueries({ queryKey: ['control-sessions', selectedPlanId] })
    },
    onError: (error: Error) => message.error(error.message),
  })
  const operate = useMutation({
    mutationFn: (actionType: string) => executeControlAction(controlSessionId!, actionType, crypto.randomUUID()),
    onSuccess: async (item) => {
      message.success(item.effect?.message || '仿真调控操作已完成')
      await queryClient.invalidateQueries({ queryKey: ['control-session', controlSessionId] })
    },
    onError: (error: Error) => message.error(error.message),
  })
  const rollback = useMutation({
    mutationFn: () => rollbackControlSession(controlSessionId!, crypto.randomUUID()),
    onSuccess: async () => {
      message.success('已恢复原供电拓扑，本次演练记录已保存')
      await queryClient.invalidateQueries({ queryKey: ['control-session', controlSessionId] })
    },
    onError: (error: Error) => message.error(error.message),
  })
  const deleteHistory = useMutation({
    mutationFn: deleteMaintenancePlan,
    onSuccess: async (_, deletedId) => {
      if (selectedPlanId === deletedId) {
        setSelectedPlanId(undefined)
        setSelectedScenarioId(undefined)
        setControlSessionId(undefined)
        queryClient.removeQueries({ queryKey: ['maintenance-plan', deletedId] })
      }
      message.success('历史分析及其仿真、调控记录已永久删除')
      await queryClient.invalidateQueries({ queryKey: ['maintenance-plans'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  return <>
    <PageHeader
      title="异常影响与备用供电"
      description="选择一台异常设备，系统自动模拟它暂时不可用，并告诉你受影响区域、可用备用线路以及切换后是否安全。"
    />
    <Alert
      showIcon type="info" style={{ marginBottom: 16 }}
      message="只需两步：选择异常设备 → 点击“一键分析”"
      description="系统会自动找出所有备用路径并逐一试算。这里不会操作真实设备，所有结果都会保存，刷新页面也能继续查看。"
    />
    <Row gutter={16}>
      <Col span={7}>
        <Card title="第1步：选择异常设备" className="content-card">
          <Space direction="vertical" size={14} style={{ width: '100%' }}>
            <Select
              showSearch optionFilterProp="label" value={deviceCode} placeholder="选择一台变压器"
              onChange={setDeviceCode} style={{ width: '100%' }}
              options={(topology.data?.transformers || []).map((item) => {
                const current = metrics.data?.find((row) => row.transformerCode === item.transformerCode)
                return { value: item.transformerCode, label: `${item.transformerCode} · ${item.areaName} · ${plainStatus(current?.status)}` }
              })}
            />
            {transformer ? <DeviceSummary
              code={transformer.transformerCode} area={transformer.areaName} feeder={transformer.feederCode}
              loadRate={metric?.loadRate} status={metric?.status} alertType={latestAlert?.alertType}
            /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="从实时监控中选择一台设备" />}
            <div>
              <Typography.Text type="secondary">模拟备用供电时长</Typography.Text>
              <Select value={duration} onChange={setDuration} style={{ width: '100%', marginTop: 6 }} options={[
                { value: 30, label: '30分钟' }, { value: 60, label: '1小时（推荐）' }, { value: 120, label: '2小时' },
              ]} />
            </div>
            <Button
              block size="large" type="primary" icon={<PlayCircleOutlined />}
              disabled={!deviceCode || user.roleCode === 'VIEWER'} loading={analysis.isPending}
              onClick={() => analysis.mutate()}
            >第2步：一键分析影响和备用方案</Button>
            {user.roleCode === 'VIEWER' && <Typography.Text type="secondary">只读账号可以查看历史分析，不能创建新分析。</Typography.Text>}
          </Space>
        </Card>
        <Card title="历史分析" className="content-card" style={{ marginTop: 16 }}>
          <List
            size="small" dataSource={(plans.data || []).slice(0, 12)} locale={{ emptyText: '还没有分析记录' }}
            renderItem={(item) => <List.Item
              className={selectedPlanId === item.id ? 'planning-list-item selected' : 'planning-list-item'}
              onClick={() => openHistory(item.id)}
              actions={user.roleCode === 'VIEWER' ? undefined : [
                <Popconfirm
                  key="delete" title="永久删除这条历史分析？"
                  description="对应的候选方案、仿真点、审核和调控演练记录也会从数据库一并删除。"
                  okText="删除" cancelText="取消" okButtonProps={{ danger: true }}
                  onConfirm={(event) => { event?.stopPropagation(); deleteHistory.mutate(item.id) }}
                  onCancel={(event) => event?.stopPropagation()}
                >
                  <Button
                    type="text" danger size="small" icon={<DeleteOutlined />} aria-label={`删除${item.outageDeviceCode}历史分析`}
                    loading={deleteHistory.isPending && deleteHistory.variables === item.id}
                    onClick={(event) => event.stopPropagation()}
                  />
                </Popconfirm>,
              ]}
            >
              <Space direction="vertical" size={1}>
                <Typography.Text strong>{item.outageDeviceCode} · {dayjs(item.createdAt).format('MM-DD HH:mm')}</Typography.Text>
                <Typography.Text type="secondary">{simplePlanStatus(item.status)}</Typography.Text>
              </Space>
            </List.Item>}
          />
        </Card>
      </Col>
      <Col span={17}>
        {!detail.data ? <Card className="content-card"><Empty description="选择设备并开始分析后，结果会显示在这里" /></Card>
          : <ResultPanel
              detail={detail.data} scenarios={scenarios} selected={selectedScenario} best={best}
              running={stillRunning || analysis.isPending} progress={progress} topology={topology.data}
              frame={frame} onSelect={(id) => { setSelectedScenarioId(id); setControlSessionId(undefined) }}
              control={control.data} controlSessions={controlSessions.data || []} onOpenControl={setControlSessionId}
              onCreateControl={() => createControl.mutate()} onOperate={(action) => operate.mutate(action)}
              onRollback={() => rollback.mutate()} controlLoading={createControl.isPending || operate.isPending || rollback.isPending}
            />}
      </Col>
    </Row>
  </>
}

function DeviceSummary({ code, area, feeder, loadRate, status, alertType }: {
  code: string; area: string; feeder?: string; loadRate?: number; status?: string; alertType?: string
}) {
  const abnormal = status && !['NORMAL', 'WATCH'].includes(status)
  return <Card size="small" style={{ background: abnormal ? '#fff7e6' : '#f6ffed' }}>
    <Space direction="vertical" size={4}>
      <Space><Typography.Text strong>{code}</Typography.Text><Tag color={abnormal ? 'orange' : 'green'}>{plainStatus(status)}</Tag></Space>
      <span>所在区域：{area}</span>
      <span>当前负载：{loadRate == null ? '等待数据' : `${(Number(loadRate) * 100).toFixed(1)}%`}</span>
      <span>供电线路：{feeder || '未设置'}</span>
      {alertType && <Typography.Text type="danger">最近预警：{plainAlert(alertType)}</Typography.Text>}
    </Space>
  </Card>
}

function ResultPanel({
  detail, scenarios, selected, best, running, progress, topology, frame, onSelect, control, controlSessions,
  onOpenControl, onCreateControl, onOperate, onRollback, controlLoading,
}: {
  detail: MaintenancePlanDetail; scenarios: TransferScenario[]; selected?: TransferScenario; best?: TransferScenario;
  running: boolean; progress: number; topology?: any; frame: SimulationPoint[]; onSelect: (id: number) => void;
  control?: ControlSession; controlSessions: ControlSession[]; onOpenControl: (id: number) => void;
  onCreateControl: () => void; onOperate: (action: string) => void; onRollback: () => void; controlLoading: boolean
}) {
  const summary = selected?.summary
  const area = detail.impact.impactedTransformers.map((item) => item.areaName).join('、') || detail.outageDeviceCode
  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Card className="content-card" title="分析进度">
      {running ? <><Progress percent={progress} status="active" /><Typography.Text>正在自动测试 {Math.max(1, scenarios.length)} 条备用路径，请稍候……</Typography.Text></>
        : <Alert showIcon type={best?.summary?.feasible ? 'success' : 'error'}
            message={best?.summary?.feasible ? '找到可以使用的备用供电方案' : '暂时没有安全的备用供电方案'}
            description={best?.summary?.feasible
              ? `${area}的负荷可以尝试通过备用线路转移，预计最高负载率${Number(best.summary.maximumLoadPercent).toFixed(1)}%，低于95%安全线。`
              : '系统测试的备用路径存在过载或时间冲突。建议降低用电负荷、缩短故障持续时间，或交由专业人员制定其他方案。'} />}
    </Card>
    <Row gutter={12}>
      <Col span={8}><Card><Statistic title="可能受影响的区域" value={area} /></Card></Col>
      <Col span={8}><Card><Statistic title="需要转移的用电负荷" value={Number(detail.impact.estimatedTransferLoadKw)} precision={1} suffix="kW" /></Card></Col>
      <Col span={8}><Card><Statistic title="找到的备用路径" value={scenarios.length} suffix="条" /></Card></Col>
    </Row>
    <Card className="content-card" title="备用方案比较">
      <Row gutter={[12, 12]}>
        {scenarios.map((item, index) => <Col span={12} key={item.id}>
          <Card
            size="small" hoverable onClick={() => onSelect(item.id)}
            style={{ borderColor: selected?.id === item.id ? '#1677ff' : undefined }}
            title={<Space><span>方案{index + 1}</span>{item.id === best?.id && <Tag color="green">系统推荐</Tag>}</Space>}
          >
            <Space direction="vertical" size={5}>
              <span>备用方向：{item.sourceFeederCode} → {item.targetFeederCode}</span>
              <span>接收负荷的设备：{item.targetTransformerCode}</span>
              <span>使用备用连接：{item.tieSwitchCode}</span>
              {item.summary ? <>
                <span>预计最高负载：<strong>{Number(item.summary.maximumLoadPercent).toFixed(1)}%</strong></span>
                <Tag icon={item.summary.feasible ? <CheckCircleOutlined /> : undefined} color={item.summary.feasible ? 'green' : 'red'}>
                  {item.summary.feasible ? '安全线内，可以使用' : '可能过载，不建议使用'}
                </Tag>
              </> : <Tag color="processing">正在计算</Tag>}
            </Space>
          </Card>
        </Col>)}
      </Row>
    </Card>
    {topology && selected && <Card className="content-card" title={<Space><SafetyCertificateOutlined />图上看懂备用供电路径</Space>}>
      <Alert type="info" showIcon style={{ marginBottom: 12 }} message="灰色设备是模拟故障设备；绿色粗线是备用供电方向；黄色或红色表示转移后负荷偏高。" />
      <GridTopologyChart topology={topology} scenario={selected} frame={frame} height={470} />
      {summary && Number(summary.backgroundViolationPointCount || 0) > 0 && <Typography.Text type="secondary">
        图中其他黄色或红色设备属于原有监控风险，不是本次模拟造成的。
      </Typography.Text>}
    </Card>}
    {!running && selected?.summary?.feasible && <ManualControlPanel
      selected={selected} topology={topology} control={control} controlSessions={controlSessions}
      onOpen={onOpenControl} onCreate={onCreateControl} onOperate={onOperate} onRollback={onRollback}
      loading={controlLoading}
    />}
    <Alert showIcon type="warning" message="所有调控均为系统内仿真，不会连接或操作真实开关" description="系统会持久化每次点击、操作前后状态、拓扑变化和负载结果。真实设备操作必须由有资质的现场人员按正式规程完成。" />
  </Space>
}

function ManualControlPanel({ selected, topology, control, controlSessions, onOpen, onCreate, onOperate, onRollback, loading }: {
  selected: TransferScenario; topology?: any; control?: ControlSession; controlSessions: ControlSession[];
  onOpen: (id: number) => void; onCreate: () => void; onOperate: (action: string) => void; onRollback: () => void; loading: boolean
}) {
  if (!control) return <Card className="content-card" title={<Space><ControlOutlined />手动仿真调控</Space>}>
    <Alert showIcon type="success" message="分析已经完成，现在可以亲自操作备用供电切换" description="系统会锁定安全顺序：先隔离异常设备，再接通备用线路，随后转移负荷并核验效果。每一步点击后，拓扑和负载会立即变化。" />
    <Button type="primary" size="large" icon={<ControlOutlined />} loading={loading} onClick={onCreate} style={{ marginTop: 16 }}>
      进入手动仿真调控台
    </Button>
  </Card>

  const finished = ['COMPLETED', 'ROLLED_BACK'].includes(control.status)
  const controlScenario = control.scenario || selected
  return <Card className="content-card" title={<Space><ControlOutlined />手动仿真调控台 <Tag color="blue">仅仿真</Tag></Space>}>
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {controlSessions.length > 1 && <Select
        value={control.id} onChange={onOpen} style={{ width: 360 }}
        options={controlSessions.map((item) => ({ value: item.id, label: `演练 #${item.id} · ${controlStatus(item.status)}` }))}
      />}
      <Steps
        current={control.currentStepNo}
        status={control.status === 'FAILED' ? 'error' : control.status === 'COMPLETED' ? 'finish' : 'process'}
        size="small" items={(control.operationChecklist || []).map((item) => ({ title: item.buttonText, description: item.deviceCode }))}
      />
      <Alert
        showIcon type={control.supplyState === 'BACKUP' ? 'success' : control.supplyState === 'INTERRUPTED' ? 'warning' : 'info'}
        message={supplyText(control.supplyState)} description={control.effect?.message}
      />
      <Row gutter={12}>
        <Col span={8}><Card size="small"><Statistic title="原线路开关" value={control.sourceSwitchState === 'OPEN' ? '已断开' : '已接通'} valueStyle={{ color: control.sourceSwitchState === 'OPEN' ? '#d46b08' : '#389e0d' }} /></Card></Col>
        <Col span={8}><Card size="small"><Statistic title="备用线路开关" value={control.tieSwitchState === 'CLOSED' ? '已接通' : '未接通'} valueStyle={{ color: control.tieSwitchState === 'CLOSED' ? '#389e0d' : '#8c8c8c' }} /></Card></Col>
        <Col span={8}><Card size="small"><Statistic title="负荷状态" value={control.transferred ? '已转移' : '未转移'} suffix={control.effect?.maximumLoadPercent ? ` · ${Number(control.effect.maximumLoadPercent).toFixed(1)}%` : ''} /></Card></Col>
      </Row>
      {topology && <GridTopologyChart
        topology={topology} scenario={controlScenario} frame={control.frame || []} height={500}
        controlState={{
          sourceSwitchState: control.sourceSwitchState, tieSwitchState: control.tieSwitchState,
          supplyState: control.supplyState, transferred: control.transferred,
        }}
      />}
      {!finished && control.nextAction && <Card size="small" style={{ borderColor: '#1677ff', background: '#f0f7ff' }}>
        <Space direction="vertical" size={10}>
          <Typography.Text strong>下一步：{control.nextAction.instruction}</Typography.Text>
          {control.nextAction.deviceCode && <Typography.Text type="secondary">操作对象：{control.nextAction.deviceCode}</Typography.Text>}
          <Space>
            <Popconfirm
              title={`确认在仿真中执行“${control.nextAction.buttonText}”？`} description="操作会被记录，但不会发送到真实电网设备。"
              onConfirm={() => onOperate(control.nextAction!.actionType)} okText="确认执行" cancelText="取消"
            >
              <Button type="primary" size="large" loading={loading}>{control.nextAction.buttonText}</Button>
            </Popconfirm>
            {control.currentStepNo > 0 && <Popconfirm title="恢复原供电状态并结束本次演练？" onConfirm={onRollback}>
              <Button icon={<RollbackOutlined />} disabled={loading}>撤销并恢复</Button>
            </Popconfirm>}
          </Space>
        </Space>
      </Card>}
      {control.status === 'COMPLETED' && <Alert showIcon type="success" message="仿真调控完成" description={`备用供电已经建立并通过负载核验。最高预计负载率 ${Number(control.effect?.maximumLoadPercent || 0).toFixed(1)}%。`} />}
      {control.status === 'ROLLED_BACK' && <Alert showIcon type="info" message="本次演练已撤销，拓扑恢复到原供电状态" />}
      {(control.actions || []).length > 0 && <Card size="small" title="操作记录（已保存）">
        <Timeline items={(control.actions || []).map((item) => ({
          color: item.actionType === 'ROLLBACK' ? 'orange' : 'green',
          children: <span><strong>第{item.sequenceNo}步：</strong>{item.instruction} · {item.operatorName} · {dayjs(item.createdAt).format('HH:mm:ss')}</span>,
        }))} />
      </Card>}
    </Space>
  </Card>
}

function chooseBest(items: TransferScenario[]) {
  return [...items].sort((a, b) => {
    if (Boolean(a.summary?.feasible) !== Boolean(b.summary?.feasible)) return a.summary?.feasible ? -1 : 1
    return Number(a.summary?.maximumLoadRate || 99) - Number(b.summary?.maximumLoadRate || 99)
  })[0]
}

function lastFrame(points: SimulationPoint[]) {
  if (!points.length) return []
  const times = [...new Set(points.map((item) => item.timePoint))].sort()
  return points.filter((item) => item.timePoint === times[times.length - 1])
}

function plainStatus(status?: string) {
  return ({ NORMAL: '运行正常', WATCH: '需要关注', HISTORICAL_ANOMALY: '比平时明显偏高', OVERLOAD: '已经过载' } as Record<string, string>)[status || ''] || '等待实时数据'
}

function plainAlert(type: string) {
  return type === 'OVERLOAD' ? '用电负荷超过设备容量' : '用电负荷比平时明显偏高'
}

function simplePlanStatus(status: string) {
  if (status === 'SIMULATING') return '正在分析备用方案'
  if (['READY', 'APPROVED'].includes(status)) return '分析已完成'
  if (status === 'ARCHIVED') return '已归档'
  return '分析记录已保存'
}

function controlStatus(status: ControlSession['status']) {
  return ({ READY: '待开始', RUNNING: '调控中', COMPLETED: '已完成', ROLLED_BACK: '已恢复', FAILED: '失败' } as Record<string, string>)[status]
}

function supplyText(state: ControlSession['supplyState']) {
  return ({ NORMAL: '当前由原线路供电', INTERRUPTED: '异常设备已隔离，等待备用线路接管', BACKUP: '备用线路正在供电' } as Record<string, string>)[state]
}
