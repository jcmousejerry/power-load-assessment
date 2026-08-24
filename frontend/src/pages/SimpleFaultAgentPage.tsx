import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert, Button, Card, Col, Collapse, Empty, Input, List, Popconfirm, Progress, Row, Segmented, Select, Space, Spin,
  Statistic, Steps, Tag, Timeline, Typography, message,
} from 'antd'
import { CheckOutlined, DeleteOutlined, PauseCircleOutlined, RobotOutlined, SearchOutlined, ToolOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useEffect, useMemo, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import { useNavigate, useSearchParams } from 'react-router-dom'
import remarkGfm from 'remark-gfm'
import {
  cancelAgentRun, createAgentSession, decideAgentApproval, deleteAgentSession, getAgentSession, getPlanningTopology,
  listAgentSessions, listGridAlerts, listGridMetrics, sendAgentMessage,
} from '../api/services'
import GridTopologyChart from '../components/GridTopologyChart'
import PageHeader from '../components/PageHeader'
import type { AgentApproval, AgentRun, AgentSession, AgentStep, TransferScenario } from '../types'

export default function SimpleFaultAgentPage() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [deviceCode, setDeviceCode] = useState<string | undefined>(searchParams.get('device') || undefined)
  const [sessionId, setSessionId] = useState<number>()
  const [taskGoal, setTaskGoal] = useState('RECOVER')
  const [extraRequirement, setExtraRequirement] = useState('')
  const topology = useQuery({ queryKey: ['planning-topology'], queryFn: getPlanningTopology })
  const metrics = useQuery({ queryKey: ['grid-metrics'], queryFn: listGridMetrics, refetchInterval: 3000 })
  const alerts = useQuery({ queryKey: ['grid-alerts-agent-simple'], queryFn: () => listGridAlerts({ limit: 50 }), refetchInterval: 5000 })
  const sessions = useQuery({ queryKey: ['agent-sessions'], queryFn: listAgentSessions, refetchInterval: 5000 })
  const detail = useQuery({
    queryKey: ['agent-session', sessionId], queryFn: () => getAgentSession(sessionId!), enabled: Boolean(sessionId),
    refetchInterval: sessionId ? 1000 : false,
  })
  const transformer = topology.data?.transformers.find((item) => item.transformerCode === deviceCode)
  const metric = metrics.data?.find((item) => item.transformerCode === deviceCode)
  const alert = alerts.data?.find((item) => item.transformerCode === deviceCode)
  const latestRun = detail.data?.runs?.[0]
  const draft = useMemo(() => parseJson(detail.data?.draftJson), [detail.data?.draftJson])
  const draftDeviceCode = draft?.request?.transformerCode as string | undefined
  const draftTransformer = topology.data?.transformers.find((item) => item.transformerCode === draftDeviceCode)
  const recommended = draft?.preview?.recommended
  const candidates = draft?.preview?.candidates || []
  const execution = draft?.execution
  const visualScenario = useMemo(() => toScenario(draft), [draft])

  const analyze = useMutation({
    mutationFn: async () => {
      if (!deviceCode || !transformer) throw new Error('请先选择一台设备')
      const session = await createAgentSession(`${deviceCode}异常处理`)
      setSessionId(session.id)
      const start = dayjs().add(5, 'minute').second(0)
      const duration = taskGoal === 'LONG' ? 2 : 1
      const end = start.add(duration, 'hour')
      const loadText = metric ? `${(Number(metric.loadRate) * 100).toFixed(1)}%` : '暂时未知'
      const goalText = taskGoal === 'LONG' ? '保守检查未来两小时并准备备用供电' : taskGoal === 'COMPARE' ? '尽可能比较所有备用路径并选出风险最低的一条' : '尽快恢复受影响区域的模拟供电'
      const prompt = `任务目标：${goalText}。实时监控发现${deviceCode}（${transformer.areaName}）当前${plainStatus(metric?.status)}，负载率${loadText}。请把该设备视为暂时不可用，处理从${start.format('YYYY-MM-DD HH:mm')}到${end.format('HH:mm')}的影响，使用P90和95%安全线。请自主调查实时状态、历史趋势、预警、拓扑和全部备用路径；找到安全路径后请求我的一次性执行授权。授权后请自主创建正式处置任务、运行全部路径仿真、选择风险最低方案，并严格按照安全顺序亲自完成确认、隔离原线路、接通备用线路、转移负荷和结果核验五步仿真调控。${extraRequirement ? `补充要求：${extraRequirement}。` : ''}`
      await sendAgentMessage(session.id, prompt)
      return session.id
    },
    onSuccess: async () => {
      message.success('AI已开始读取实时状态、设备连接和备用能力')
      await queryClient.invalidateQueries({ queryKey: ['agent-sessions'] })
    },
    onError: (error: Error) => message.error(error.message),
  })
  const approve = useMutation({
    mutationFn: (item: AgentApproval) => decideAgentApproval(item.id, true),
    onSuccess: async () => {
      message.success('授权成功，Agent正在自主运行正式仿真并完成五步仿真调控')
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['agent-session', sessionId] }),
        queryClient.invalidateQueries({ queryKey: ['agent-sessions'] }),
      ])
    },
  })
  const reject = useMutation({
    mutationFn: (item: AgentApproval) => decideAgentApproval(item.id, false),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['agent-session', sessionId] }),
  })
  const cancel = useMutation({
    mutationFn: () => cancelAgentRun(latestRun!.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['agent-session', sessionId] }),
  })
  const deleteHistory = useMutation({
    mutationFn: deleteAgentSession,
    onSuccess: async (_, deletedId) => {
      const remaining = (sessions.data || []).filter((item) => item.id !== deletedId)
      queryClient.setQueryData<AgentSession[]>(['agent-sessions'], remaining)
      if (sessionId === deletedId) {
        setSessionId(remaining[0]?.id)
        queryClient.removeQueries({ queryKey: ['agent-session', deletedId] })
      }
      message.success('AI分析、消息、步骤和审批记录已从数据库删除')
      await queryClient.invalidateQueries({ queryKey: ['agent-sessions'] })
    },
    onError: (error: Error) => message.error(error.message),
  })
  useEffect(() => {
    if (!sessionId && sessions.data?.length) setSessionId(sessions.data[0].id)
  }, [sessionId, sessions.data])
  useEffect(() => {
    if (draftDeviceCode) setDeviceCode(draftDeviceCode)
  }, [draftDeviceCode])

  const pending = detail.data?.approvals?.find((item) => item.status === 'PENDING')
  const assistantMessage = [...(detail.data?.messages || [])].reverse().find((item) => item.role === 'ASSISTANT')
  const running = latestRun && ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(latestRun.status)

  return <>
    <PageHeader
      title="AI 异常处置 Agent"
      description="交给Agent一个异常处置目标，它会根据每次结果自主选择下一项工具，并在一次授权后完成正式仿真、方案选择和五步仿真调控。"
    />
    <Alert
      showIcon type="info" style={{ marginBottom: 16 }}
      message="模型会观察工具结果再决定下一步，不是后台写死的一条流程"
      description="只读调查会自动完成；一次授权后，Agent会自主创建任务、比较全部正式仿真、选择最低风险路径并执行五步仿真调控。每轮模型决定、工具输入、真实结果和完成校验都会持久化。所有调控仅作用于仿真状态，不连接真实电网设备。"
    />
    <Row gutter={16}>
      <Col span={7}>
        <Card className="content-card" title="第1步：选择要处理的设备">
          <Space direction="vertical" size={14} style={{ width: '100%' }}>
            <Select
              showSearch optionFilterProp="label" value={deviceCode} style={{ width: '100%' }} placeholder="选择实时监控设备"
              onChange={setDeviceCode} options={(topology.data?.transformers || []).map((item) => {
                const current = metrics.data?.find((row) => row.transformerCode === item.transformerCode)
                return { value: item.transformerCode, label: `${item.transformerCode} · ${item.areaName} · ${plainStatus(current?.status)}` }
              })}
            />
            {transformer ? <Card size="small" style={{ background: metric?.status === 'OVERLOAD' ? '#fff1f0' : metric?.status === 'NORMAL' ? '#f6ffed' : '#fff7e6' }}>
              <Space direction="vertical" size={4}>
                <Space><strong>{transformer.transformerCode}</strong><Tag color={metric?.status === 'OVERLOAD' ? 'red' : metric?.status === 'NORMAL' ? 'green' : 'orange'}>{plainStatus(metric?.status)}</Tag></Space>
                <span>所在区域：{transformer.areaName}</span>
                <span>当前负载：{metric ? `${(Number(metric.loadRate) * 100).toFixed(1)}%` : '等待数据'}</span>
                {alert && <Typography.Text type="danger">最近告警：{alert.alertType === 'OVERLOAD' ? '超过设备容量' : '明显高于平时'}</Typography.Text>}
              </Space>
            </Card> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请选择设备" />}
            <div>
              <Typography.Text strong>第2步：选择Agent要完成的任务</Typography.Text>
              <Segmented
                block value={taskGoal} onChange={(value) => setTaskGoal(String(value))} style={{ marginTop: 8 }}
                options={[
                  { label: '快速恢复', value: 'RECOVER' }, { label: '两小时保守检查', value: 'LONG' }, { label: '完整比较路径', value: 'COMPARE' },
                ]}
              />
            </div>
            <Input.TextArea
              value={extraRequirement} onChange={(event) => setExtraRequirement(event.target.value)}
              placeholder="可选：补充要求，例如“优先选择负载最低的备用设备”" autoSize={{ minRows: 2, maxRows: 4 }}
            />
            <Button
              block size="large" type="primary" icon={<RobotOutlined />} disabled={!deviceCode || !transformer}
              loading={analyze.isPending} onClick={() => analyze.mutate()}
            >第3步：交给Agent执行调查</Button>
          </Space>
        </Card>
        <Card className="content-card" title="最近分析" style={{ marginTop: 16 }}>
          <List
            size="small" dataSource={(sessions.data || []).slice(0, 10)} locale={{ emptyText: '还没有AI分析记录' }}
            renderItem={(item) => <List.Item
              className={sessionId === item.id ? 'planning-list-item selected' : 'planning-list-item'}
              onClick={() => setSessionId(item.id)}
              actions={[
                <Popconfirm
                  key="delete" title="永久删除这条AI分析？"
                  description="会话消息、Agent运行步骤、工具结果和审批记录都会一并删除；已授权生成的正式处置方案会保留。"
                  okText="删除" cancelText="取消" okButtonProps={{ danger: true }}
                  onConfirm={(event) => { event?.stopPropagation(); deleteHistory.mutate(item.id) }}
                  onCancel={(event) => event?.stopPropagation()}
                >
                  <Button
                    type="text" danger size="small" icon={<DeleteOutlined />} aria-label={`删除${item.title}`}
                    loading={deleteHistory.isPending && deleteHistory.variables === item.id}
                    onClick={(event) => event.stopPropagation()}
                  />
                </Popconfirm>,
              ]}
            >
              <Space direction="vertical" size={1}>
                <Typography.Text strong>{item.title}</Typography.Text>
                <Typography.Text type="secondary">{item.linkedPlanId ? '处理方案已保存' : '仅保留分析结果'} · {dayjs(item.updatedAt).format('MM-DD HH:mm')}</Typography.Text>
              </Space>
            </List.Item>}
          />
        </Card>
      </Col>
      <Col span={17}>
        {!detail.data ? <Card className="content-card"><Empty description="AI分析结果会显示在这里" /></Card> : <Space direction="vertical" size={16} style={{ width: '100%' }}>
          {running && <Card className="content-card">
            <Space><Spin /><Typography.Text strong>Agent正在工作：{latestRun?.currentStage || '准备工具'}</Typography.Text></Space>
            <Progress percent={Math.min(90, Math.max(15, (latestRun?.steps?.length || 1) * 12))} status="active" style={{ marginTop: 12 }} />
            <Button danger ghost icon={<PauseCircleOutlined />} loading={cancel.isPending} onClick={() => cancel.mutate()}>取消本次任务</Button>
          </Card>}
          {latestRun && <AgentWorkbench run={latestRun} />}
          {recommended && <>
            <Alert
              showIcon type={recommended.feasible ? 'success' : 'error'}
              message={recommended.feasible ? 'AI找到了一条可用的备用供电方案' : 'AI暂时没有找到安全的备用供电方案'}
              description={recommended.feasible
                ? `建议将${draft.request.transformerCode}的负荷转向${recommended.targetFeederCode}方向，由${recommended.targetTransformerCode}临时接收。预计最高负载率${Number(recommended.maximumLoadPercent).toFixed(1)}%。`
                : '测试的备用方向可能导致其他设备过载，建议减少用电负荷或联系专业人员进一步处理。'}
            />
            <Row gutter={12}>
              <Col span={8}><Card><Statistic title="发生了什么" value={`${draft.request.transformerCode}暂时不可用`} /></Card></Col>
              <Col span={8}><Card><Statistic title="可能影响哪里" value={draftTransformer?.areaName || draft.request.transformerCode} /></Card></Col>
              <Col span={8}><Card><Statistic title="备用方案是否安全" value={recommended.feasible ? '可以使用' : '不建议使用'} valueStyle={{ color: recommended.feasible ? '#389e0d' : '#cf1322' }} /></Card></Col>
            </Row>
            <Card className="content-card" title="图上查看AI建议">
              <Alert type="info" showIcon style={{ marginBottom: 12 }} message="灰色是模拟异常设备，绿色粗线是建议的备用供电方向。黄色或红色表示负荷偏高。" />
              {topology.data && visualScenario && <GridTopologyChart topology={topology.data} scenario={visualScenario} height={450} />}
            </Card>
            <Card className="content-card" title="AI比较了哪些备用方向">
              <Row gutter={[12, 12]}>{candidates.map((item: any, index: number) => <Col span={12} key={item.tieSwitchCode}>
                <Card size="small" title={`备用方向${index + 1}`}>
                  <Space direction="vertical">
                    <span>{item.sourceFeederCode} → {item.targetFeederCode}</span>
                    <span>接收设备：{item.targetTransformerCode}</span>
                    <span>预计最高负载：{Number(item.maximumLoadPercent).toFixed(1)}%</span>
                    <Tag color={item.feasible ? 'green' : 'red'}>{item.feasible ? '安全线内' : '可能过载'}</Tag>
                  </Space>
                </Card>
              </Col>)}</Row>
            </Card>
          </>}
          {assistantMessage && <Card className="content-card" title="AI的白话说明">
            <div className="agent-markdown">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{assistantMessage.content}</ReactMarkdown>
            </div>
          </Card>}
          {pending && <ApprovalPanel approval={pending} loading={approve.isPending || reject.isPending} onApprove={() => approve.mutate(pending)} onReject={() => reject.mutate(pending)} />}
          {execution && <ExecutionResult execution={execution} onOpen={() => navigate(`/maintenance-planning?plan=${execution.planId}`)} />}
          {detail.data.linkedPlanId && <Alert
            showIcon type="success" message={`Agent已经完成处置任务 #${detail.data.linkedPlanId}`}
            description={<Space direction="vertical">
              <span>{execution?.completed ? 'Agent已亲自完成五步仿真调控并通过结果核验，所有动作和状态变化均已持久化。' : 'Agent正在继续完成正式仿真和自主调控，可以在工作台查看每一轮决定。'}</span>
              <Button type="primary" onClick={() => navigate(`/maintenance-planning?plan=${detail.data!.linkedPlanId}`)}>查看正式方案与Agent调控记录</Button>
            </Space>}
          />}
        </Space>}
      </Col>
    </Row>
  </>
}

function ApprovalPanel({ approval, loading, onApprove, onReject }: { approval: AgentApproval; loading: boolean; onApprove: () => void; onReject: () => void }) {
  const preview = parseJson(approval.previewJson) || {}
  return <Card className="agent-approval-card" title={<Space><CheckOutlined />一次性授权：让Agent完成后续工作</Space>}>
    <Space direction="vertical" size={10}>
      <span>异常设备：<strong>{preview.transformerCode}</strong></span>
      <span>建议方向：{preview.sourceFeederCode} → {preview.targetFeederCode}</span>
      <span>预计最高负载：{Number(preview.maximumLoadPercent).toFixed(1)}%</span>
      <Alert
        type="warning" showIcon message="批准后Agent会自主完成正式仿真和五步仿真调控，但不会控制真实设备"
        description="Agent会根据工具返回逐轮决定：创建持久化处置任务、运行全部备用路径仿真、选择最低风险方案，再依次完成确认、隔离原线路、接通备用线路、转移负荷和核验。安全顺序由后端强制校验，此授权只对本次仿真任务有效。"
      />
      <Space><Button type="primary" loading={loading} onClick={onApprove}>授权Agent执行本次任务</Button><Button disabled={loading} onClick={onReject}>拒绝，只保留调查结果</Button></Space>
    </Space>
  </Card>
}

function ExecutionResult({ execution, onOpen }: { execution: any; onOpen: () => void }) {
  const completed = Boolean(execution.completed && execution.verified)
  const stepNo = Number(execution.controlStepNo || 0)
  return <Card className="content-card" title={<Space><RobotOutlined />Agent自主调控结果</Space>}>
    <Alert
      showIcon type={completed ? 'success' : 'info'}
      message={completed ? 'Agent已经自主完成五步仿真调控并核验通过' : `Agent正在执行：${execution.nextGoal || '准备下一项工作'}`}
      description={completed
        ? '这不是只生成了一段建议：正式任务、候选方案、逐时仿真、最低风险方案和五项调控动作都已经实际执行并写入数据库。'
        : '模型会读取上一项工具结果后继续决定下一步；页面刷新不会丢失已完成的动作。'}
    />
    <Row gutter={12} style={{ marginTop: 14 }}>
      <Col span={6}><Statistic title="正式候选" value={Number(execution.candidateCount || 0)} suffix="条" /></Col>
      <Col span={6}><Statistic title="正式仿真" value={(execution.simulationTaskIds || []).length} suffix="次" /></Col>
      <Col span={6}><Statistic title="调控动作" value={stepNo} suffix="/ 5" /></Col>
      <Col span={6}><Statistic title="结果核验" value={execution.verified ? '通过' : '进行中'} valueStyle={{ color: execution.verified ? '#389e0d' : undefined }} /></Col>
    </Row>
    {execution.planId && <Button type="link" style={{ paddingLeft: 0 }} onClick={onOpen}>查看方案、仿真曲线和完整调控审计</Button>}
  </Card>
}

function AgentWorkbench({ run }: { run: AgentRun }) {
  const steps = run.steps || []
  const active = steps.find((item) => item.status === 'RUNNING')
  const completed = steps.filter((item) => item.status === 'SUCCEEDED' || item.status === 'COMPLETED').length
  return <Card className="content-card" title={<Space><ToolOutlined />Agent任务工作台 <Tag color={run.status === 'FAILED' ? 'red' : run.status === 'COMPLETED' ? 'green' : 'processing'}>{runStatus(run.status)}</Tag></Space>}>
    <Row gutter={12} style={{ marginBottom: 14 }}>
      <Col span={6}><Statistic title="已完成步骤" value={completed} suffix={`/ ${steps.length}`} /></Col>
      <Col span={6}><Statistic title="模型自主决定" value={steps.filter((item) => item.stepType === 'DECISION').length} /></Col>
      <Col span={6}><Statistic title="实际工具调用" value={steps.filter((item) => item.stepType === 'TOOL').length} /></Col>
      <Col span={6}><Statistic title="模型Token" value={Number(run.inputTokens || 0) + Number(run.outputTokens || 0)} /></Col>
    </Row>
    {active && <Alert type="info" showIcon message={`正在执行：${plainStep(active.toolName, active.summary)}`} style={{ marginBottom: 14 }} />}
    <Timeline items={steps.map((item) => ({
      color: item.status === 'FAILED' ? 'red' : item.status === 'RUNNING' ? 'blue' : item.stepType === 'PLAN' ? 'purple' : 'green',
      dot: item.stepType === 'TOOL' ? <SearchOutlined /> : item.stepType === 'DECISION' ? <RobotOutlined /> : undefined,
      children: <div className="agent-step">
        <Space wrap><Typography.Text strong>{plainStep(item.toolName, item.summary)}</Typography.Text><Tag>{stepStatus(item.status)}</Tag></Space>
        <div className="agent-step-meta">
          <Typography.Text type="secondary">第 {item.stepNo} 步 · {dayjs(item.createdAt).format('HH:mm:ss.SSS')}</Typography.Text>
          {item.toolName && <Typography.Text type="secondary">工具：{item.toolName}</Typography.Text>}
        </div>
        <AgentStepDetails step={item} />
      </div>,
    }))} />
  </Card>
}

function AgentStepDetails({ step }: { step: AgentStep }) {
  const detail = parseJson(step.detailJson)
  if (!detail) return <Typography.Text type="secondary">本步骤没有附加数据。</Typography.Text>
  const structured = detail.action || detail.input || detail.output || detail.verification
  const items = structured ? [
    detail.action && { label: '具体动作', value: detail.action },
    detail.input && { label: '输入参数', value: detail.input },
    detail.output && { label: '执行结果 / 证据', value: detail.output },
    detail.verification && { label: '完成校验', value: detail.verification },
    detail.durationMs != null && { label: '实际耗时', value: `${detail.durationMs} ms` },
  ].filter(Boolean) as { label: string; value: unknown }[] : [{ label: '执行结果 / 证据', value: detail }]
  return <Collapse
    ghost size="small" className="agent-step-collapse"
    items={[{
      key: 'details', label: '展开查看这一步具体做了什么',
      children: <Space direction="vertical" size={10} style={{ width: '100%' }}>
        {items.map((item) => <div key={item.label} className="agent-step-detail-block">
          <Typography.Text strong>{item.label}</Typography.Text>
          <StepValue value={item.value} />
        </div>)}
      </Space>,
    }]}
  />
}

function StepValue({ value }: { value: unknown }) {
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return <Typography.Paragraph style={{ marginBottom: 0 }}>{String(value)}</Typography.Paragraph>
  }
  return <pre className="agent-step-json">{JSON.stringify(value, null, 2)}</pre>
}

function plainStep(tool: string | undefined, fallback: string) {
  return ({
    inspect_live_anomaly: '核实实时负载、最近预警和一小时变化趋势', get_device_profile: '确认设备位置和基本信息', get_topology_neighbors: '追踪上游线路和附近备用连接',
    inspect_planning_topology: '调查供电拓扑、联络开关和可用承载设备',
    calculate_outage_impact: '计算设备不可用后可能影响的负荷', generate_transfer_candidates: '生成可尝试的备用方案',
    simulate_transfer_scenario: '预演备用路径并检查是否过载', verify_safety_constraints: '交叉核验容量、路径、操作顺序和供电恢复',
    create_response_case: '创建可追踪的异常处置任务', build_formal_scenarios: '生成正式仿真方案',
    run_formal_simulations: '运行并等待全部路径的正式逐时仿真', select_lowest_risk_scenario: '从正式结果中选择风险最低的安全路径',
    create_autonomous_control: '创建由Agent操作的仿真调控会话', execute_simulated_control_action: '按安全顺序执行一项仿真调控动作',
    inspect_control_state: '核验调控状态、动作记录和下一项动作', request_execution_approval: '请求本次自主仿真调控的一次性授权',
    prepare_manual_control: '准备可直接操作的手动仿真调控台',
    understand_task: '理解任务目标并提取执行参数', evaluate_transfer_options: '读取负荷曲线并实际计算全部备用路径',
    explain_findings: '依据工具证据生成白话说明',
  } as Record<string, string>)[tool || ''] || fallback
}

function stepStatus(status: string) {
  return ({ RUNNING: '执行中', SUCCEEDED: '已完成', COMPLETED: '已完成', FAILED: '失败' } as Record<string, string>)[status] || status
}

function runStatus(status: string) {
  return ({ RUNNING: '工作中', WAITING_APPROVAL: '等待授权', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消', CANCEL_REQUESTED: '正在取消' } as Record<string, string>)[status] || status
}

function plainStatus(status?: string) {
  return ({ NORMAL: '运行正常', WATCH: '需要关注', HISTORICAL_ANOMALY: '负荷比平时明显偏高', OVERLOAD: '负荷已经超过容量' } as Record<string, string>)[status || ''] || '等待实时数据'
}

function parseJson(value?: string) {
  try { return value ? JSON.parse(value) : undefined } catch { return undefined }
}

function toScenario(draft: any): TransferScenario | undefined {
  const item = draft?.preview?.recommended
  const request = draft?.request
  if (!item || !request) return undefined
  return {
    id: 0, planId: 0, scenarioName: 'AI建议', versionNo: 0, sourceFeederCode: item.sourceFeederCode,
    targetFeederCode: item.targetFeederCode, targetTransformerCode: item.targetTransformerCode,
    tieSwitchCode: item.tieSwitchCode, loadBlocksJson: JSON.stringify([request.transformerCode]),
    loadBlocks: [request.transformerCode], status: item.feasible ? 'SUCCEEDED' : 'FAILED', steps: item.steps || [],
    createdAt: '', updatedAt: '',
  }
}
