import {
  ApartmentOutlined, CheckCircleOutlined, CopyOutlined, EditOutlined, PauseCircleOutlined,
  PlayCircleOutlined, PlusOutlined, ReloadOutlined, SendOutlined, ThunderboltOutlined,
} from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert, Button, Card, Col, DatePicker, Descriptions, Divider, Empty, Form, Input, InputNumber,
  List, message, Modal, Progress, Radio, Row, Select, Slider, Space, Statistic, Steps, Table, Tabs,
  Tag, Timeline, Typography,
} from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect, useMemo, useState } from 'react'
import {
  archiveMaintenancePlan, cloneTransferScenario, createMaintenancePlan, generateTransferCandidates,
  getMaintenancePlan, getPlanningTopology, getTransferSimulationPoints, listMaintenancePlans,
  reviewMaintenancePlan, saveTransferSteps, startTransferSimulation, submitMaintenancePlan,
  updateMaintenancePlan,
} from '../api/services'
import GridTopologyChart from '../components/GridTopologyChart'
import PageHeader from '../components/PageHeader'
import { useAuthStore } from '../store/auth'
import type {
  MaintenancePlan, MaintenancePlanDetail, PlanningTopology, SimulationPoint, TransferOperationStep,
  TransferScenario,
} from '../types'

type PlanForm = {
  planName: string
  reason: string
  outageDeviceType: 'TRANSFORMER' | 'FEEDER' | 'SWITCH'
  outageDeviceCode: string
  timeRange: [Dayjs, Dayjs]
  safetyPercent: number
  loadProfileType: 'P50' | 'P90' | 'HISTORICAL_DAY'
  historicalDate?: Dayjs
  responsiblePerson?: string
}

const statusMeta: Record<string, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: 'default' }, SIMULATING: { label: '仿真中', color: 'processing' },
  READY: { label: '待提交', color: 'cyan' }, UNDER_REVIEW: { label: '审核中', color: 'blue' },
  APPROVED: { label: '已批准', color: 'success' }, REJECTED: { label: '已驳回', color: 'error' },
  ARCHIVED: { label: '已归档', color: 'default' }, SUCCEEDED: { label: '仿真完成', color: 'success' },
  FAILED: { label: '仿真失败', color: 'error' }, STALE: { label: '需要重算', color: 'warning' },
}

export default function MaintenancePlanningPage() {
  const user = useAuthStore((state) => state.user)!
  const queryClient = useQueryClient()
  const topology = useQuery({ queryKey: ['planning-topology'], queryFn: getPlanningTopology })
  const plans = useQuery({ queryKey: ['maintenance-plans'], queryFn: listMaintenancePlans, refetchInterval: 5000 })
  const [selectedPlanId, setSelectedPlanId] = useState<number>()
  const detail = useQuery({
    queryKey: ['maintenance-plan', selectedPlanId],
    queryFn: () => getMaintenancePlan(selectedPlanId!),
    enabled: Boolean(selectedPlanId),
    refetchInterval: (query) => {
      const value = query.state.data
      return value?.status === 'SIMULATING' || value?.scenarios.some((item) => item.status === 'SIMULATING') ? 1000 : 4000
    },
  })
  const [selectedScenarioId, setSelectedScenarioId] = useState<number>()
  const [editorOpen, setEditorOpen] = useState(false)
  const [editingPlan, setEditingPlan] = useState<MaintenancePlanDetail>()
  const [planForm] = Form.useForm<PlanForm>()

  useEffect(() => {
    if (!selectedPlanId && plans.data?.length) setSelectedPlanId(plans.data[0].id)
  }, [plans.data, selectedPlanId])
  useEffect(() => {
    const scenarios = detail.data?.scenarios || []
    if (!scenarios.some((item) => item.id === selectedScenarioId)) {
      setSelectedScenarioId(scenarios[0]?.id)
    }
  }, [detail.data?.scenarios, selectedScenarioId])

  const refresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['maintenance-plans'] }),
      queryClient.invalidateQueries({ queryKey: ['maintenance-plan', selectedPlanId] }),
    ])
  }
  const createMutation = useMutation({
    mutationFn: (values: PlanForm) => createMaintenancePlan(toCreatePayload(values)),
    onSuccess: async (created) => {
      message.success('检修计划草稿已创建')
      setEditorOpen(false); planForm.resetFields(); setSelectedPlanId(created.id); await refresh()
    },
    onError: (error) => message.error(error.message),
  })
  const updateMutation = useMutation({
    mutationFn: (values: PlanForm) => updateMaintenancePlan(editingPlan!.id, toUpdatePayload(values)),
    onSuccess: async () => { message.success('计划已更新，需要重新运行仿真'); setEditorOpen(false); setEditingPlan(undefined); await refresh() },
    onError: (error) => message.error(error.message),
  })

  const openCreate = () => {
    setEditingPlan(undefined)
    planForm.setFieldsValue({
      outageDeviceType: 'TRANSFORMER', safetyPercent: 80, loadProfileType: 'P90',
      timeRange: [dayjs().add(1, 'day').hour(9).minute(0), dayjs().add(1, 'day').hour(12).minute(0)],
    })
    setEditorOpen(true)
  }
  const openEdit = () => {
    if (!detail.data) return
    setEditingPlan(detail.data)
    planForm.setFieldsValue({
      ...detail.data,
      safetyPercent: Number(detail.data.safetyLimit) * 100,
      timeRange: [dayjs(detail.data.startTime), dayjs(detail.data.endTime)],
      historicalDate: detail.data.historicalDate ? dayjs(detail.data.historicalDate) : undefined,
    })
    setEditorOpen(true)
  }
  const selectedScenario = detail.data?.scenarios.find((item) => item.id === selectedScenarioId)

  return (
    <>
      <PageHeader
        title="检修转供仿真工作台"
        description="一台变压器准备停运检修时，用这里提前找出备用供电路径，并检查负荷转过去后会不会造成其他设备过载。"
        extra={user.roleCode !== 'VIEWER' && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建检修计划</Button>}
      />
      <Alert
        showIcon
        type="info"
        style={{ marginBottom: 16 }}
        message="第一次使用：按“建计划 → 找路径 → 跑仿真 → 选方案 → 提交审核”的顺序操作"
        description="先点击右上角“新建检修计划”；创建后点击“生成候选方案”；到“拓扑与实时仿真”逐条运行方案；选择校核通过的方案后提交审核。这里只编制和试算计划，不会操作真实开关。"
      />
      <Row gutter={18} align="stretch">
        <Col span={6}>
          <Card className="content-card planning-plan-list" title="检修计划" extra={<Button type="text" icon={<ReloadOutlined />} onClick={() => void refresh()} />}>
            <List
              loading={plans.isLoading}
              dataSource={plans.data || []}
              locale={{ emptyText: '还没有检修计划' }}
              renderItem={(plan) => (
                <List.Item className={selectedPlanId === plan.id ? 'planning-list-item selected' : 'planning-list-item'} onClick={() => setSelectedPlanId(plan.id)}>
                  <Space direction="vertical" size={3} style={{ width: '100%' }}>
                    <Space style={{ justifyContent: 'space-between', width: '100%' }}>
                      <Typography.Text strong ellipsis>{plan.planName}</Typography.Text>
                      <Status status={plan.status} />
                    </Space>
                    <Typography.Text type="secondary">{plan.outageDeviceCode} · {dayjs(plan.startTime).format('MM-DD HH:mm')}～{dayjs(plan.endTime).format('HH:mm')}</Typography.Text>
                    <Typography.Text type="secondary">负责人：{plan.responsiblePerson || plan.ownerName}</Typography.Text>
                  </Space>
                </List.Item>
              )}
            />
          </Card>
        </Col>
        <Col span={18}>
          {!selectedPlanId ? <Card><Empty description="创建或选择一张检修计划开始工作" /></Card> : (
            <PlanningWorkbench
              detail={detail.data}
              loading={detail.isLoading}
              topology={topology.data}
              selectedScenario={selectedScenario}
              onScenarioChange={setSelectedScenarioId}
              onEdit={openEdit}
              onRefresh={refresh}
            />
          )}
        </Col>
      </Row>

      <Modal
        title={editingPlan ? '修改检修计划' : '新建检修计划'} width={720} open={editorOpen}
        onCancel={() => setEditorOpen(false)} onOk={() => planForm.submit()}
        confirmLoading={createMutation.isPending || updateMutation.isPending}
      >
        <PlanEditor form={planForm} topology={topology.data} editing={Boolean(editingPlan)} onFinish={(values) => {
          if (editingPlan) updateMutation.mutate(values); else createMutation.mutate(values)
        }} />
      </Modal>
    </>
  )
}

function PlanningWorkbench({ detail, loading, topology, selectedScenario, onScenarioChange, onEdit, onRefresh }: {
  detail?: MaintenancePlanDetail
  loading: boolean
  topology?: PlanningTopology
  selectedScenario?: TransferScenario
  onScenarioChange: (id?: number) => void
  onEdit: () => void
  onRefresh: () => Promise<void>
}) {
  const user = useAuthStore((state) => state.user)!
  const [steps, setSteps] = useState<TransferOperationStep[]>([])
  const [reviewOpen, setReviewOpen] = useState(false)
  const [reviewForm] = Form.useForm()
  useEffect(() => setSteps(selectedScenario?.steps || []), [selectedScenario?.id, selectedScenario?.steps])

  const candidatesMutation = useMutation({
    mutationFn: () => generateTransferCandidates(detail!.id),
    onSuccess: async (items) => { message.success(`已生成${items.length}个候选方案`); onScenarioChange(items[0]?.id); await onRefresh() },
    onError: (error) => message.error(error.message),
  })
  const cloneMutation = useMutation({
    mutationFn: () => cloneTransferScenario(selectedScenario!.id),
    onSuccess: async (item) => { message.success('方案副本已创建'); onScenarioChange(item.id); await onRefresh() },
    onError: (error) => message.error(error.message),
  })
  const stepsMutation = useMutation({
    mutationFn: () => saveTransferSteps(selectedScenario!.id, steps),
    onSuccess: async () => { message.success('操作步骤已保存'); await onRefresh() },
    onError: (error) => message.error(error.message),
  })
  const simulateMutation = useMutation({
    mutationFn: () => startTransferSimulation(selectedScenario!.id),
    onSuccess: async () => { message.success('仿真任务已启动，拓扑会随时间片更新'); await onRefresh() },
    onError: (error) => message.error(error.message),
  })
  const submitMutation = useMutation({
    mutationFn: () => submitMaintenancePlan(detail!.id, selectedScenario!.id, '人工计划工作台提交'),
    onSuccess: async () => { message.success('计划已提交管理员审核'); await onRefresh() },
    onError: (error) => message.error(error.message),
  })
  const reviewMutation = useMutation({
    mutationFn: (values: { decision: 'APPROVE' | 'REJECT'; comment?: string }) => reviewMaintenancePlan(detail!.id, values.decision, values.comment),
    onSuccess: async () => { message.success('审核结果已保存'); setReviewOpen(false); await onRefresh() },
    onError: (error) => message.error(error.message),
  })
  const archiveMutation = useMutation({
    mutationFn: () => archiveMaintenancePlan(detail!.id),
    onSuccess: async () => { message.success('计划已归档'); await onRefresh() },
    onError: (error) => message.error(error.message),
  })

  if (loading || !detail) return <Card loading />
  const editable = user.roleCode !== 'VIEWER' && !['UNDER_REVIEW', 'APPROVED', 'ARCHIVED'].includes(detail.status)
  const scenarioOptions = detail.scenarios.map((item) => ({ value: item.id, label: `${item.scenarioName} · ${statusMeta[item.status]?.label || item.status}` }))

  return (
    <Card className="content-card planning-workbench" title={(
      <Space><ApartmentOutlined /><span>{detail.planName}</span><Status status={detail.status} /></Space>
    )} extra={<Space>
      {editable && <Button icon={<EditOutlined />} onClick={onEdit}>修改计划</Button>}
      {editable && <Button loading={candidatesMutation.isPending} onClick={() => candidatesMutation.mutate()}>生成候选方案</Button>}
      {selectedScenario?.status === 'SUCCEEDED' && editable && <Button type="primary" icon={<SendOutlined />} loading={submitMutation.isPending} onClick={() => submitMutation.mutate()}>提交审核</Button>}
      {user.roleCode === 'ADMIN' && detail.status === 'UNDER_REVIEW' && detail.ownerId !== user.userId && <Button type="primary" onClick={() => setReviewOpen(true)}>审核计划</Button>}
    </Space>}>
      <Alert
        type={detail.impact.conflicts.length ? 'warning' : 'info'} showIcon style={{ marginBottom: 16 }}
        message={`${detail.outageDeviceCode} · ${dayjs(detail.startTime).format('YYYY-MM-DD HH:mm')}～${dayjs(detail.endTime).format('HH:mm')} · 安全线 ${(Number(detail.safetyLimit) * 100).toFixed(0)}%`}
        description={`预计转供负荷 ${Number(detail.impact.estimatedTransferLoadKw).toFixed(1)} kW；历史口径 ${detail.loadProfileType}；数据来源 ${dataSourceLabel(detail.impact.dataSource)}；发现 ${detail.impact.conflicts.length} 个计划冲突。`}
      />
      <Tabs items={[
        { key: 'overview', label: '计划与影响范围', children: <Overview detail={detail} onArchive={() => archiveMutation.mutate()} /> },
        { key: 'simulation', label: '拓扑与实时仿真', children: topology ? <SimulationWorkspace topology={topology} detail={detail} scenario={selectedScenario} options={scenarioOptions} onScenarioChange={onScenarioChange} onClone={() => cloneMutation.mutate()} onSimulate={() => simulateMutation.mutate()} /> : <Empty /> },
        { key: 'steps', label: '操作步骤编排', children: selectedScenario ? <StepEditor steps={steps} setSteps={setSteps} editable={editable} saving={stepsMutation.isPending} onSave={() => stepsMutation.mutate()} /> : <Empty description="请先生成并选择候选方案" /> },
        { key: 'compare', label: '方案比较与审核', children: <Comparison detail={detail} /> },
      ]} />

      <Modal title="审核检修计划" open={reviewOpen} onCancel={() => setReviewOpen(false)} onOk={() => reviewForm.submit()} confirmLoading={reviewMutation.isPending}>
        <Form form={reviewForm} layout="vertical" onFinish={reviewMutation.mutate} initialValues={{ decision: 'APPROVE' }}>
          <Form.Item name="decision" label="审核结果"><Radio.Group options={[{ value: 'APPROVE', label: '批准' }, { value: 'REJECT', label: '驳回' }]} /></Form.Item>
          <Form.Item name="comment" label="审核意见"><Input.TextArea rows={4} placeholder="驳回时必须填写原因" /></Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}

function Overview({ detail, onArchive }: { detail: MaintenancePlanDetail; onArchive: () => void }) {
  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Descriptions bordered size="small" column={3}>
      <Descriptions.Item label="停运对象">{detail.outageDeviceType} · {detail.outageDeviceCode}</Descriptions.Item>
      <Descriptions.Item label="源馈线">{detail.impact.sourceFeederCode}</Descriptions.Item>
      <Descriptions.Item label="负责人">{detail.responsiblePerson || detail.ownerName}</Descriptions.Item>
      <Descriptions.Item label="预计转供负荷">{Number(detail.impact.estimatedTransferLoadKw).toFixed(1)} kW</Descriptions.Item>
      <Descriptions.Item label="受影响变压器">{detail.impact.impactedTransformerCodes.join('、')}</Descriptions.Item>
      <Descriptions.Item label="可用联络路径">{detail.impact.alternativeFeeders.length} 条</Descriptions.Item>
      <Descriptions.Item label="检修原因" span={3}>{detail.reason}</Descriptions.Item>
    </Descriptions>
    <Row gutter={12}>
      {detail.impact.alternativeFeeders.map((item) => <Col span={8} key={item.switchCode}><Card size="small" title={item.targetFeederName}><Space direction="vertical"><Tag color="cyan">{item.switchCode}</Tag><span>接入 {item.targetFeederCode}</span><span>馈线容量 {Number(item.ratedCapacityKw).toFixed(0)} kW</span></Space></Card></Col>)}
    </Row>
    {detail.impact.conflicts.length > 0 && <Alert type="error" showIcon message="存在重叠检修计划" description={detail.impact.conflicts.map((item) => `${item.planName}（${item.deviceCode}）`).join('；')} />}
    {!['SIMULATING', 'UNDER_REVIEW', 'APPROVED', 'ARCHIVED'].includes(detail.status) && <Button danger onClick={onArchive}>归档计划</Button>}
  </Space>
}

function SimulationWorkspace({ topology, detail, scenario, options, onScenarioChange, onClone, onSimulate }: {
  topology: PlanningTopology; detail: MaintenancePlanDetail; scenario?: TransferScenario
  options: Array<{ value: number; label: string }>; onScenarioChange: (id?: number) => void
  onClone: () => void; onSimulate: () => void
}) {
  const task = scenario?.latestTask
  const points = useQuery({
    queryKey: ['simulation-points', task?.id],
    queryFn: () => getTransferSimulationPoints(task!.id), enabled: Boolean(task?.id),
    refetchInterval: task && ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(task.status) ? 1000 : false,
  })
  const grouped = useMemo(() => groupPoints(points.data || []), [points.data])
  const [frameIndex, setFrameIndex] = useState(0)
  const [playing, setPlaying] = useState(false)
  useEffect(() => { setFrameIndex(0); setPlaying(false) }, [task?.id])
  useEffect(() => {
    if (!playing || grouped.length < 2) return
    const timer = window.setInterval(() => setFrameIndex((value) => value >= grouped.length - 1 ? 0 : value + 1), 900)
    return () => window.clearInterval(timer)
  }, [grouped.length, playing])
  const current = grouped[frameIndex]
  const summary = scenario?.summary || safeJson(scenario?.summaryJson)
  return <Space direction="vertical" size={14} style={{ width: '100%' }}>
    <Space wrap>
      <Select style={{ width: 390 }} placeholder="选择候选方案" value={scenario?.id} onChange={onScenarioChange} options={options} />
      {scenario && <Button icon={<CopyOutlined />} onClick={onClone}>复制方案</Button>}
      {scenario && <Button type="primary" icon={<ThunderboltOutlined />} onClick={onSimulate} disabled={scenario.status === 'SIMULATING'}>运行仿真</Button>}
      {task && <Tag color={task.dataSource === 'CLICKHOUSE' ? 'green' : 'orange'}>{dataSourceLabel(task.dataSource)}</Tag>}
    </Space>
    {task && ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(task.status) && <Card size="small"><Progress percent={Number(task.progress)} status="active" /><Typography.Text type="secondary">{task.stage}</Typography.Text></Card>}
    {task?.errorMessage && <Alert type="error" showIcon message="仿真失败" description={task.errorMessage} />}
    {scenario ? <>
      <Card size="small" title={<Space><span>动态拓扑</span>{current && <Tag color="blue">{dayjs(current.time).format('MM-DD HH:mm')}</Tag>}</Space>} extra={grouped.length > 0 && <Space><Button type="text" icon={playing ? <PauseCircleOutlined /> : <PlayCircleOutlined />} onClick={() => setPlaying(!playing)}>{playing ? '暂停' : '播放'}</Button><span>时间片 {frameIndex + 1}/{grouped.length}</span></Space>}>
        <GridTopologyChart topology={topology} scenario={scenario} frame={current?.points || []} height={500} />
        {grouped.length > 1 && <Slider min={0} max={grouped.length - 1} value={frameIndex} onChange={(value) => { setPlaying(false); setFrameIndex(value) }} tooltip={{ formatter: (value) => value === undefined ? '' : dayjs(grouped[value]?.time).format('HH:mm') }} />}
      </Card>
      {summary && <Row gutter={12}>
        <Col span={6}><Card><Statistic title="方案校核" value={summary.feasible ? '通过' : '未通过'} valueStyle={{ color: summary.feasible ? '#2f9e88' : '#e03131' }} prefix={summary.feasible ? <CheckCircleOutlined /> : undefined} /></Card></Col>
        <Col span={6}><Card><Statistic title="最大负载率" value={Number(summary.maximumLoadPercent)} precision={1} suffix="%" /></Card></Col>
        <Col span={6}><Card><Statistic title="风险点" value={summary.violationPointCount} suffix="个" /></Card></Col>
        <Col span={6}><Card><Statistic title="预计缺供电量" value={Number(summary.unservedEnergyKwh)} precision={1} suffix="kWh" /></Card></Col>
      </Row>}
      {summary && Number(summary.backgroundViolationPointCount || 0) > 0 && <Alert
        type="warning"
        showIcon
        message="发现与本次转供无关的存量风险"
        description={`全网背景风险设备：${(summary.backgroundRiskEquipment || []).join('、') || '未知'}。这些风险会在拓扑中保留标色，但不会错误阻断当前方案；当前方案只按转供目标设备和目标馈线新增风险判定。`}
      />}
    </> : <Empty description="点击“生成候选方案”后，可在拓扑上查看路径并运行仿真" />}
  </Space>
}

function StepEditor({ steps, setSteps, editable, saving, onSave }: {
  steps: TransferOperationStep[]; setSteps: (steps: TransferOperationStep[]) => void; editable: boolean; saving: boolean; onSave: () => void
}) {
  const move = (index: number, delta: number) => {
    const copy = [...steps]; const target = index + delta
    if (target < 0 || target >= copy.length) return
    ;[copy[index], copy[target]] = [copy[target], copy[index]]
    setSteps(copy.map((item, i) => ({ ...item, stepNo: i + 1 })))
  }
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Alert type="info" showIcon message="这些步骤用于规划和仿真，不会发送到真实开关。拖动效果通过上移/下移保存到数据库。" />
    <Table rowKey={(item) => `${item.stepNo}-${item.deviceCode || ''}`} pagination={false} dataSource={steps} columns={[
      { title: '序号', dataIndex: 'stepNo', width: 70 },
      { title: '类型', dataIndex: 'stepType', width: 145, render: (value, item, index) => editable ? <Select value={value} style={{ width: 135 }} onChange={(next) => setSteps(steps.map((row, i) => i === index ? { ...row, stepType: next } : row))} options={['SAFETY_CHECK', 'OPEN_SWITCH', 'CLOSE_SWITCH', 'TRANSFER_LOAD', 'RESTORE'].map((v) => ({ value: v }))} /> : value },
      { title: '设备', dataIndex: 'deviceCode', width: 130, render: (value, item, index) => editable ? <Input value={value} onChange={(event) => setSteps(steps.map((row, i) => i === index ? { ...row, deviceCode: event.target.value } : row))} /> : value || '-' },
      { title: '操作说明', dataIndex: 'instruction', render: (value, item, index) => editable ? <Input value={value} onChange={(event) => setSteps(steps.map((row, i) => i === index ? { ...row, instruction: event.target.value } : row))} /> : value },
      { title: '调整', width: 170, render: (_, item, index) => editable && <Space><Button size="small" disabled={index === 0} onClick={() => move(index, -1)}>上移</Button><Button size="small" disabled={index === steps.length - 1} onClick={() => move(index, 1)}>下移</Button><Button size="small" danger onClick={() => setSteps(steps.filter((_, i) => i !== index).map((row, i) => ({ ...row, stepNo: i + 1 })))}>删除</Button></Space> },
    ]} />
    {editable && <Space><Button icon={<PlusOutlined />} onClick={() => setSteps([...steps, { stepNo: steps.length + 1, stepType: 'SAFETY_CHECK', instruction: '新增现场确认步骤' }])}>新增步骤</Button><Button type="primary" loading={saving} onClick={onSave}>保存步骤</Button></Space>}
  </Space>
}

function Comparison({ detail }: { detail: MaintenancePlanDetail }) {
  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Table rowKey="id" pagination={false} dataSource={detail.scenarios} columns={[
      { title: '方案', dataIndex: 'scenarioName' },
      { title: '路径', render: (_, item) => `${item.sourceFeederCode} → ${item.tieSwitchCode} → ${item.targetFeederCode}` },
      { title: '承载设备', dataIndex: 'targetTransformerCode' },
      { title: '校核', render: (_, item) => item.summary ? <Tag color={item.summary.feasible ? 'green' : 'red'}>{item.summary.feasible ? '通过' : '未通过'}</Tag> : <Status status={item.status} /> },
      { title: '最大负载率', render: (_, item) => item.summary ? `${Number(item.summary.maximumLoadPercent).toFixed(1)}%` : '-' },
      { title: '风险点', render: (_, item) => item.summary?.violationPointCount ?? '-' },
      { title: '操作步数', render: (_, item) => item.summary?.operationStepCount ?? item.steps.length },
    ]} />
    <Divider orientation="left">审核轨迹</Divider>
    {detail.reviews.length ? <Timeline items={detail.reviews.map((item) => ({ color: item.action === 'REJECT' ? 'red' : 'green', children: <><strong>{item.operatorName} · {item.action}</strong><div>{item.comment || '无补充意见'} · {dayjs(item.createdAt).format('YYYY-MM-DD HH:mm')}</div></> }))} /> : <Empty description="尚未提交审核" />}
  </Space>
}

function PlanEditor({ form, topology, editing, onFinish }: { form: any; topology?: PlanningTopology; editing: boolean; onFinish: (values: PlanForm) => void }) {
  const deviceType = Form.useWatch('outageDeviceType', form) || 'TRANSFORMER'
  const profileType = Form.useWatch('loadProfileType', form)
  const options = deviceType === 'FEEDER'
    ? topology?.feeders.map((item) => ({ value: item.feederCode, label: `${item.feederCode} · ${item.feederName}` }))
    : deviceType === 'SWITCH'
      ? topology?.switches.map((item) => ({ value: item.switchCode, label: `${item.switchCode} · ${item.switchName}` }))
      : topology?.transformers.map((item) => ({ value: item.transformerCode, label: `${item.transformerCode} · ${item.transformerName} · ${item.feederCode}` }))
  return <Form form={form} layout="vertical" onFinish={onFinish}>
    <Row gutter={14}>
      <Col span={14}><Form.Item name="planName" label="计划名称" rules={[{ required: true }]}><Input placeholder="例如：T003周三上午检修" /></Form.Item></Col>
      <Col span={10}><Form.Item name="responsiblePerson" label="现场负责人"><Input /></Form.Item></Col>
      {!editing && <><Col span={8}><Form.Item name="outageDeviceType" label="停运对象类型" rules={[{ required: true }]}><Select onChange={() => form.setFieldValue('outageDeviceCode', undefined)} options={[{ value: 'TRANSFORMER', label: '变压器' }, { value: 'FEEDER', label: '馈线' }, { value: 'SWITCH', label: '开关' }]} /></Form.Item></Col><Col span={16}><Form.Item name="outageDeviceCode" label="停运对象" rules={[{ required: true }]}><Select showSearch optionFilterProp="label" options={options} /></Form.Item></Col></>}
      <Col span={24}><Form.Item name="reason" label="检修原因" rules={[{ required: true }]}><Input.TextArea rows={2} /></Form.Item></Col>
      <Col span={24}><Form.Item name="timeRange" label="计划检修时间" rules={[{ required: true }]}><DatePicker.RangePicker showTime format="YYYY-MM-DD HH:mm" style={{ width: '100%' }} /></Form.Item></Col>
      <Col span={8}><Form.Item name="safetyPercent" label="容量安全线（%）" rules={[{ required: true }]}><InputNumber min={50} max={100} style={{ width: '100%' }} /></Form.Item></Col>
      <Col span={8}><Form.Item name="loadProfileType" label="负荷估算口径" rules={[{ required: true }]}><Select options={[{ value: 'P50', label: 'P50典型负荷' }, { value: 'P90', label: 'P90保守负荷' }, { value: 'HISTORICAL_DAY', label: '指定历史日期' }]} /></Form.Item></Col>
      {profileType === 'HISTORICAL_DAY' && <Col span={8}><Form.Item name="historicalDate" label="历史日期" rules={[{ required: true }]}><DatePicker style={{ width: '100%' }} /></Form.Item></Col>}
    </Row>
  </Form>
}

function Status({ status }: { status: string }) {
  const meta = statusMeta[status] || { label: status, color: 'default' }
  return <Tag color={meta.color}>{meta.label}</Tag>
}

function groupPoints(points: SimulationPoint[]) {
  const values = new Map<string, SimulationPoint[]>()
  points.forEach((point) => values.set(point.timePoint, [...(values.get(point.timePoint) || []), point]))
  return Array.from(values.entries()).map(([time, group]) => ({ time, points: group })).sort((a, b) => a.time.localeCompare(b.time))
}

function safeJson(value?: string) {
  try { return value ? JSON.parse(value) : undefined } catch { return undefined }
}

function dataSourceLabel(value?: string) {
  if (value === 'CLICKHOUSE') return 'ClickHouse历史负荷画像'
  if (value === 'LIVE_FALLBACK') return '实时负荷降级计算'
  return value || '等待数据'
}

function toCreatePayload(values: PlanForm) {
  return {
    planName: values.planName, reason: values.reason, outageDeviceType: values.outageDeviceType,
    outageDeviceCode: values.outageDeviceCode, startTime: values.timeRange[0].format('YYYY-MM-DDTHH:mm:ss'),
    endTime: values.timeRange[1].format('YYYY-MM-DDTHH:mm:ss'), safetyLimit: values.safetyPercent / 100,
    loadProfileType: values.loadProfileType, historicalDate: values.historicalDate?.format('YYYY-MM-DD'), responsiblePerson: values.responsiblePerson,
  }
}

function toUpdatePayload(values: PlanForm) {
  const { outageDeviceType: _type, outageDeviceCode: _code, ...payload } = toCreatePayload(values)
  return payload
}
