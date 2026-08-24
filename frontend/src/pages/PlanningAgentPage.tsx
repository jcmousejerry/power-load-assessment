import {
  CheckOutlined, CloseOutlined, PlusOutlined, RobotOutlined, SendOutlined, StopOutlined,
  ToolOutlined,
} from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert, Avatar, Button, Card, Col, Empty, Input, List, message, Progress, Row, Space, Spin,
  Statistic, Tag, Timeline, Typography,
} from 'antd'
import dayjs from 'dayjs'
import { useEffect, useMemo, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import { useNavigate } from 'react-router-dom'
import remarkGfm from 'remark-gfm'
import {
  cancelAgentRun, createAgentSession, decideAgentApproval, getAgentSession, getPlanningTopology,
  listAgentSessions, sendAgentMessage,
} from '../api/services'
import GridTopologyChart from '../components/GridTopologyChart'
import PageHeader from '../components/PageHeader'
import type { AgentApproval, AgentRun, AgentSession, TransferScenario } from '../types'

export default function PlanningAgentPage() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const sessions = useQuery({ queryKey: ['agent-sessions'], queryFn: listAgentSessions, refetchInterval: 5000 })
  const topology = useQuery({ queryKey: ['planning-topology'], queryFn: getPlanningTopology })
  const [selectedId, setSelectedId] = useState<number>()
  const detail = useQuery({
    queryKey: ['agent-session', selectedId], queryFn: () => getAgentSession(selectedId!), enabled: Boolean(selectedId),
    refetchInterval: (query) => {
      const latest = query.state.data?.runs?.[0]
      return latest && ['RUNNING', 'CANCEL_REQUESTED', 'WAITING_APPROVAL'].includes(latest.status) ? 800 : 3000
    },
  })
  const [input, setInput] = useState('')
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!selectedId && sessions.data?.length) setSelectedId(sessions.data[0].id)
  }, [selectedId, sessions.data])
  useEffect(() => bottomRef.current?.scrollIntoView({ behavior: 'smooth' }), [detail.data?.messages?.length])

  const refresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['agent-sessions'] }),
      queryClient.invalidateQueries({ queryKey: ['agent-session', selectedId] }),
    ])
  }
  const createMutation = useMutation({
    mutationFn: () => createAgentSession('检修转供方案编制'),
    onSuccess: async (created) => { setSelectedId(created.id); await refresh() },
    onError: (error) => message.error(error.message),
  })
  const sendMutation = useMutation({
    mutationFn: (content: string) => sendAgentMessage(selectedId!, content),
    onSuccess: async () => { setInput(''); await refresh() },
    onError: (error) => message.error(error.message),
  })
  const approvalMutation = useMutation({
    mutationFn: ({ id, approve }: { id: number; approve: boolean }) => decideAgentApproval(id, approve),
    onSuccess: async (_, variables) => { message.success(variables.approve ? '已批准，正在写入正式计划' : '已拒绝写入'); await refresh() },
    onError: (error) => message.error(error.message),
  })
  const cancelMutation = useMutation({
    mutationFn: cancelAgentRun,
    onSuccess: refresh,
    onError: (error) => message.error(error.message),
  })

  const latestRun = detail.data?.runs?.[0]
  const draft = useMemo(() => parseDraft(detail.data?.draftJson), [detail.data?.draftJson])
  const draftScenario = useMemo(() => toVisualScenario(draft), [draft])
  const example = `${dayjs().add(1, 'day').format('YYYY-MM-DD')} 09:00到12:00检修T003，使用P90保守负荷，转供后负载率不要超过80%，帮我编制并校核方案。`

  return <>
    <PageHeader
      title="检修转供方案 Agent"
      description="用一句话交代目标，Agent会根据每次工具结果自主调查、比较方案，并在授权后完成正式仿真与五步仿真调控。"
      extra={<Button type="primary" icon={<PlusOutlined />} loading={createMutation.isPending} onClick={() => createMutation.mutate()}>新建会话</Button>}
    />
    <Alert
      showIcon type="info" style={{ marginBottom: 16 }}
      message="怎样使用：写清设备编号、完整日期、开始结束时间、P50/P90和安全线，然后点击“发送”"
      description="Agent会自主寻找转供路径并比较是否过载。一次授权后，它会创建正式计划、运行全部候选仿真、选择最低风险路径并完成五步仿真调控。所有调控仅作用于系统仿真，不控制真实设备。"
    />
    <Row gutter={16} className="agent-workspace">
      <Col span={5}>
        <Card className="content-card agent-session-list" title="研判会话">
          <List dataSource={sessions.data || []} loading={sessions.isLoading} locale={{ emptyText: '点击“新建会话”开始' }} renderItem={(session) => (
            <List.Item className={selectedId === session.id ? 'planning-list-item selected' : 'planning-list-item'} onClick={() => setSelectedId(session.id)}>
              <Space direction="vertical" size={2} style={{ width: '100%' }}>
                <Typography.Text strong ellipsis>{session.title}</Typography.Text>
                <Typography.Text type="secondary">{session.linkedPlanId ? `已生成计划 #${session.linkedPlanId}` : '尚未保存正式计划'}</Typography.Text>
                <Typography.Text type="secondary">{dayjs(session.updatedAt).format('MM-DD HH:mm')}</Typography.Text>
              </Space>
            </List.Item>
          )} />
        </Card>
      </Col>
      <Col span={11}>
        <Card className="content-card agent-chat-card" title={<Space><RobotOutlined />方案编制对话</Space>} extra={latestRun && ['RUNNING', 'CANCEL_REQUESTED'].includes(latestRun.status) && <Button danger size="small" icon={<StopOutlined />} onClick={() => cancelMutation.mutate(latestRun.id)}>停止</Button>}>
          {!detail.data ? <Empty description="选择或创建会话" /> : <>
            <div className="agent-message-list">
              {(detail.data.messages || []).map((item) => <div key={item.id} className={`agent-message ${item.role.toLowerCase()}`}>
                <Avatar size="small" icon={item.role === 'ASSISTANT' ? <RobotOutlined /> : undefined}>{item.role === 'USER' ? '我' : undefined}</Avatar>
                <div className="agent-message-bubble"><div className="agent-markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{item.content}</ReactMarkdown></div><Typography.Text type="secondary" style={{ fontSize: 11 }}>{dayjs(item.createdAt).format('HH:mm:ss')}</Typography.Text></div>
              </div>)}
              {latestRun && <RunProgress run={latestRun} />}
              {(detail.data.approvals || []).filter((item) => item.status === 'PENDING').map((item) => <ApprovalCard key={item.id} approval={item} loading={approvalMutation.isPending} onDecision={(approve) => approvalMutation.mutate({ id: item.id, approve })} />)}
              <div ref={bottomRef} />
            </div>
            <div className="agent-prompt-box">
              <Input.TextArea value={input} onChange={(event) => setInput(event.target.value)} autoSize={{ minRows: 2, maxRows: 5 }} placeholder="例如：明天09:00到12:00检修T003，安全线80%，帮我编制转供方案" onPressEnter={(event) => { if (!event.shiftKey) { event.preventDefault(); if (input.trim()) sendMutation.mutate(input.trim()) } }} />
              <Space style={{ justifyContent: 'space-between', width: '100%' }}>
                <Button type="link" onClick={() => setInput(example)}>填入完整示例</Button>
                <Button type="primary" icon={<SendOutlined />} disabled={!input.trim() || latestRun?.status === 'RUNNING'} loading={sendMutation.isPending} onClick={() => sendMutation.mutate(input.trim())}>发送</Button>
              </Space>
            </div>
          </>}
        </Card>
      </Col>
      <Col span={8}>
        <Card className="content-card agent-draft-card" title="实时方案草稿" extra={detail.data?.linkedPlanId && <Button type="link" onClick={() => navigate('/maintenance-planning')}>打开正式计划 #{detail.data.linkedPlanId}</Button>}>
          {!draft ? <Empty description="Agent完成拓扑查询和临时仿真后，方案草稿会显示在这里" /> : <Space direction="vertical" size={14} style={{ width: '100%' }}>
            <Descriptions draft={draft} />
            {topology.data && draftScenario && <GridTopologyChart topology={topology.data} scenario={draftScenario} height={360} />}
            <CandidateSummary draft={draft} />
          </Space>}
        </Card>
      </Col>
    </Row>
  </>
}

function RunProgress({ run }: { run: AgentRun }) {
  const active = ['RUNNING', 'CANCEL_REQUESTED'].includes(run.status)
  return <div className="agent-run-progress">
    <Space><Spin size="small" spinning={active} /><Typography.Text strong>{run.currentStage || run.status}</Typography.Text><Tag>{run.status}</Tag></Space>
    <Timeline style={{ marginTop: 14 }} items={(run.steps || []).map((step) => ({
      color: step.status === 'FAILED' ? 'red' : step.stepType === 'RESULT' ? 'green' : 'blue',
      dot: step.toolName ? <ToolOutlined /> : undefined,
      children: <div><Typography.Text>{step.summary}</Typography.Text>{step.toolName && <div><Tag color="geekblue">{step.toolName}</Tag></div>}</div>,
    }))} />
    {(run.inputTokens > 0 || run.outputTokens > 0) && <Typography.Text type="secondary">本次模型用量：输入 {run.inputTokens} / 输出 {run.outputTokens} Token</Typography.Text>}
  </div>
}

function ApprovalCard({ approval, loading, onDecision }: { approval: AgentApproval; loading: boolean; onDecision: (approve: boolean) => void }) {
  const preview = safeJson(approval.previewJson) || {}
  return <Card className="agent-approval-card" size="small" title={<Space><CheckOutlined />{preview.title || '需要确认写操作'}</Space>}>
    <Space direction="vertical" style={{ width: '100%' }}>
      {preview.transformerCode && <span>检修对象：<strong>{preview.transformerCode}</strong></span>}
      {preview.startTime && <span>检修时间：{dayjs(preview.startTime).format('YYYY-MM-DD HH:mm')}～{dayjs(preview.endTime).format('HH:mm')}</span>}
      {preview.tieSwitchCode && <span>推荐路径：{preview.sourceFeederCode} → {preview.tieSwitchCode} → {preview.targetFeederCode}</span>}
      {preview.maximumLoadPercent !== undefined && <span>最大预计负载率：<strong>{Number(preview.maximumLoadPercent).toFixed(1)}%</strong></span>}
      <Space><Button type="primary" icon={<CheckOutlined />} loading={loading} onClick={() => onDecision(true)}>批准并执行</Button><Button danger icon={<CloseOutlined />} disabled={loading} onClick={() => onDecision(false)}>拒绝</Button></Space>
    </Space>
  </Card>
}

function Descriptions({ draft }: { draft: any }) {
  const request = draft.request || {}
  const recommended = draft.preview?.recommended || {}
  return <Row gutter={[10, 10]}>
    <Col span={12}><Card size="small"><Statistic title="检修对象" value={request.transformerCode || '-'} /></Card></Col>
    <Col span={12}><Card size="small"><Statistic title="安全线" value={Number(request.safetyLimit || 0) * 100} suffix="%" /></Card></Col>
    <Col span={12}><Card size="small"><Statistic title="推荐承载设备" value={recommended.targetTransformerCode || '-'} /></Card></Col>
    <Col span={12}><Card size="small"><Statistic title="最大负载率" value={Number(recommended.maximumLoadPercent || 0)} precision={1} suffix="%" valueStyle={{ color: recommended.feasible ? '#2f9e88' : '#e03131' }} /></Card></Col>
  </Row>
}

function CandidateSummary({ draft }: { draft: any }) {
  const candidates = draft.preview?.candidates || []
  return <List size="small" header={<Typography.Text strong>候选方案校核结果</Typography.Text>} dataSource={candidates} renderItem={(item: any, index) => <List.Item>
    <Space direction="vertical" size={2} style={{ width: '100%' }}>
      <Space style={{ justifyContent: 'space-between', width: '100%' }}><span>候选 {index + 1}：{item.tieSwitchCode} → {item.targetFeederCode}</span><Tag color={item.feasible ? 'green' : 'red'}>{item.feasible ? '通过' : '超限'}</Tag></Space>
      <Typography.Text type="secondary">承载设备 {item.targetTransformerCode} · 最大负载率 {Number(item.maximumLoadPercent).toFixed(1)}%</Typography.Text>
    </Space>
  </List.Item>} />
}

function parseDraft(value?: string) {
  try { return value ? JSON.parse(value) : undefined } catch { return undefined }
}

function safeJson(value?: string) {
  try { return value ? JSON.parse(value) : undefined } catch { return undefined }
}

function toVisualScenario(draft: any): TransferScenario | undefined {
  const recommended = draft?.preview?.recommended
  const request = draft?.request
  if (!recommended || !request) return undefined
  return {
    id: 0, planId: 0, scenarioName: 'Agent临时方案', versionNo: 0,
    sourceFeederCode: recommended.sourceFeederCode, targetFeederCode: recommended.targetFeederCode,
    targetTransformerCode: recommended.targetTransformerCode, tieSwitchCode: recommended.tieSwitchCode,
    loadBlocksJson: JSON.stringify([request.transformerCode]), loadBlocks: [request.transformerCode],
    status: recommended.feasible ? 'SUCCEEDED' : 'FAILED', steps: recommended.steps || [], createdAt: '', updatedAt: '',
  }
}
