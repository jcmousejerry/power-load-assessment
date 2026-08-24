import { PlusOutlined, RedoOutlined, StopOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Descriptions, Drawer, Form, Input, InputNumber, Modal, Progress, Select, Space, Table, Timeline, Tooltip, message } from 'antd'
import dayjs from 'dayjs'
import { useEffect, useState } from 'react'
import { cancelTask, createPipeline, getTaskEvents, listDatasets, listPipelines, listTasks, retryTask } from '../api/services'
import PageHeader from '../components/PageHeader'
import StatusTag from '../components/StatusTag'
import type { AnalysisTask } from '../types'
import { etaConfidenceLabel, taskStageLabel, taskTypeLabel } from '../taskMeta'

type TaskForm = {
  pipelineName?: string
  datasetId: number
  clusterCount?: number
  forecastSteps?: number
  baselineType?: string
  maximumAdjustableKw?: number
}

export default function TasksPage() {
  const queryClient = useQueryClient()
  const datasets = useQuery({ queryKey: ['datasets'], queryFn: listDatasets })
  const [datasetFilter, setDatasetFilter] = useState<number>()
  const [pipelineFilter, setPipelineFilter] = useState<number>()
  const pipelines = useQuery({
    queryKey: ['pipelines', datasetFilter],
    queryFn: () => listPipelines(datasetFilter),
    enabled: Boolean(datasetFilter),
  })
  const tasks = useQuery({
    queryKey: ['tasks', datasetFilter, pipelineFilter],
    queryFn: () => listTasks({ datasetId: datasetFilter, pipelineId: pipelineFilter }),
    enabled: Boolean(datasetFilter) && pipelineFilter !== undefined,
    refetchInterval: 3000,
  })
  const [createOpen, setCreateOpen] = useState(false)
  const [selectedTask, setSelectedTask] = useState<AnalysisTask | null>(null)
  const [form] = Form.useForm<TaskForm>()

  useEffect(() => {
    if (datasetFilter === undefined && datasets.data?.length) {
      setDatasetFilter(datasets.data[0].id)
    }
  }, [datasetFilter, datasets.data])

  useEffect(() => {
    if (!datasetFilter || !pipelines.data) return
    const currentExists = pipelineFilter === 0 || pipelines.data.some((pipeline) => pipeline.id === pipelineFilter)
    if (!currentExists) {
      setPipelineFilter(pipelines.data[0]?.id ?? 0)
    }
  }, [datasetFilter, pipelineFilter, pipelines.data])
  const events = useQuery({
    queryKey: ['task-events', selectedTask?.id],
    queryFn: () => getTaskEvents(selectedTask!.id),
    enabled: Boolean(selectedTask),
    refetchInterval: selectedTask && !['SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED'].includes(selectedTask.status) ? 3000 : false,
  })

  const createMutation = useMutation({
    mutationFn: (values: TaskForm) => createPipeline({
      datasetId: values.datasetId,
      pipelineName: values.pipelineName,
      clusterCount: values.clusterCount || 3,
      forecastSteps: values.forecastSteps || 96,
      baselineType: values.baselineType || 'typical',
      maximumAdjustableKw: values.maximumAdjustableKw,
    }),
    onSuccess: (createdTasks) => {
      message.success('完整分析流水线已创建')
      setCreateOpen(false)
      if (createdTasks[0]) {
        setDatasetFilter(createdTasks[0].datasetId)
        setPipelineFilter(createdTasks[0].pipelineId)
      }
      form.resetFields()
      void queryClient.invalidateQueries({ queryKey: ['tasks'] })
      void queryClient.invalidateQueries({ queryKey: ['pipelines'] })
    },
    onError: (error) => message.error(error.message),
  })

  const requestCancel = async (task: AnalysisTask) => {
    try {
      await cancelTask(task.id)
      message.success('取消请求已提交')
      void queryClient.invalidateQueries({ queryKey: ['tasks'] })
    } catch (error) {
      message.error((error as Error).message)
    }
  }

  const requestRetry = async (task: AnalysisTask) => {
    try {
      await retryTask(task.id)
      message.success('任务已重新进入队列')
      void queryClient.invalidateQueries({ queryKey: ['tasks'] })
    } catch (error) {
      message.error((error as Error).message)
    }
  }

  return (
    <>
      <PageHeader
        title="任务中心"
        description="任务按数据集隔离，并按数据质量、特征、聚类、集群预测/基线、集群潜力的依赖顺序执行。"
        extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>创建分析流水线</Button>}
      />
      <Alert
        showIcon
        type="info"
        style={{ marginBottom: 16 }}
        message="执行顺序"
        description="数据质量检查 → 用户特征提取 → 用户聚类 → 各集群分别执行负荷预测和基线计算 → 各集群调节潜力。等待上游的任务不会占用执行队列。"
      />
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap>
          <span>当前数据源</span>
          <Select
            style={{ width: 260 }}
            placeholder="选择负荷数据源"
            value={datasetFilter}
            onChange={(value) => { setDatasetFilter(value); setPipelineFilter(undefined); setSelectedTask(null) }}
            options={(datasets.data || []).map((dataset) => ({ value: dataset.id, label: dataset.name }))}
          />
          <span>当前流水线</span>
          <Select
            style={{ width: 300 }}
            placeholder="选择分析流水线"
            loading={pipelines.isLoading}
            value={pipelineFilter}
            onChange={(value) => { setPipelineFilter(value); setSelectedTask(null) }}
            options={[
              ...(pipelines.data || []).map((pipeline) => ({
                value: pipeline.id,
                label: `${pipeline.pipelineName} · ${dayjs(pipeline.createdAt).format('MM-DD HH:mm')}`,
              })),
              { value: 0, label: '历史未分组任务' },
            ]}
          />
        </Space>
      </Card>
      <Card className="content-card">
        <Table<AnalysisTask>
          rowKey="id"
          loading={tasks.isLoading}
          dataSource={tasks.data || []}
          onRow={(task) => ({ onClick: () => setSelectedTask(task), style: { cursor: 'pointer' } })}
          scroll={{ x: 1200 }}
          columns={[
            { title: '编号', dataIndex: 'id', width: 80 },
            { title: '任务名称', dataIndex: 'taskName', width: 190 },
            { title: '类型', dataIndex: 'taskType', width: 180, render: (value) => taskTypeLabel(value) },
            { title: '状态', dataIndex: 'status', width: 110, render: (status) => <StatusTag status={status} /> },
            { title: '阶段', dataIndex: 'stage', width: 160, render: (value) => taskStageLabel(value) },
            { title: '进度', dataIndex: 'progress', width: 190, render: (value) => <Progress percent={Number(value)} size="small" /> },
            {
              title: '排队信息',
              width: 240,
              render: (_, task) => task.status === 'QUEUED' ? (
                <Tooltip title={`预计开始：${task.estimatedStartAt ? dayjs(task.estimatedStartAt).format('MM-DD HH:mm:ss') : '-'}`}>
                  <span>前方 {task.queuedAheadCount ?? '-'} 个任务 · 预计等待 {formatDuration(task.estimatedWaitSeconds)}</span>
                </Tooltip>
              ) : '-',
            },
            { title: '创建时间', dataIndex: 'createdAt', width: 170, render: (value) => dayjs(value).format('MM-DD HH:mm:ss') },
            {
              title: '操作',
              fixed: 'right',
              width: 100,
              render: (_, task) => {
                if (['WAITING_DEPENDENCY', 'QUEUED', 'RUNNING'].includes(task.status)) {
                  return <Button type="link" danger icon={<StopOutlined />} onClick={(event) => { event.stopPropagation(); void requestCancel(task) }}>取消</Button>
                }
                if (task.status === 'FAILED') {
                  return <Button type="link" icon={<RedoOutlined />} onClick={(event) => { event.stopPropagation(); void requestRetry(task) }}>重试</Button>
                }
                return '-'
              },
            },
          ]}
        />
      </Card>

      <Modal title="创建完整分析流水线" width={640} open={createOpen} onCancel={() => setCreateOpen(false)} onOk={() => form.submit()} confirmLoading={createMutation.isPending}>
        <Alert type="warning" showIcon style={{ marginBottom: 16 }} message="将自动创建有依赖关系的任务；不同数据集之间完全隔离。" />
        <Form form={form} layout="vertical" initialValues={{ forecastSteps: 96, clusterCount: 3, baselineType: 'typical' }} onFinish={(values) => createMutation.mutate(values)}>
          <Form.Item name="datasetId" label="数据集" rules={[{ required: true }]}>
            <Select options={(datasets.data || []).map((dataset) => ({ value: dataset.id, label: dataset.name }))} />
          </Form.Item>
          <Form.Item name="pipelineName" label="流水线名称"><Input placeholder="不填写时使用数据集名称" /></Form.Item>
          <Form.Item name="clusterCount" label="聚类数量" tooltip="聚类完成后，将为每个集群分别创建预测、基线和潜力任务"><InputNumber min={2} max={6} style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="forecastSteps" label="每个集群的预测点数"><InputNumber min={1} max={192} style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="baselineType" label="潜力计算使用的基线"><Select options={[{ value: 'mean', label: '均值基线' }, { value: 'max', label: '最大包络' }, { value: 'min', label: '最小包络' }, { value: 'quantile30', label: '30%分位数' }, { value: 'typical', label: '典型曲线' }]} /></Form.Item>
          <Form.Item name="maximumAdjustableKw" label="每个集群最大可调功率（kW，可不填）"><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
        </Form>
      </Modal>

      <Drawer title={selectedTask?.taskName} width={600} open={Boolean(selectedTask)} onClose={() => setSelectedTask(null)}>
        {selectedTask && (
          <>
            <Descriptions column={2} bordered size="small">
              <Descriptions.Item label="任务编号">{selectedTask.id}</Descriptions.Item>
              <Descriptions.Item label="状态"><StatusTag status={selectedTask.status} /></Descriptions.Item>
              <Descriptions.Item label="任务类型">{taskTypeLabel(selectedTask.taskType)}</Descriptions.Item>
              <Descriptions.Item label="资源池">{selectedTask.resourcePool}</Descriptions.Item>
              <Descriptions.Item label="当前阶段">{taskStageLabel(selectedTask.stage)}</Descriptions.Item>
              <Descriptions.Item label="进度">{selectedTask.progress}%</Descriptions.Item>
              <Descriptions.Item label="前方任务数量">{selectedTask.status === 'QUEUED' ? selectedTask.queuedAheadCount ?? '-' : '-'}</Descriptions.Item>
              <Descriptions.Item label="预计排队时间">{selectedTask.status === 'QUEUED' ? formatDuration(selectedTask.estimatedWaitSeconds) : '-'}</Descriptions.Item>
              <Descriptions.Item label="预计开始时间">{selectedTask.estimatedStartAt ? dayjs(selectedTask.estimatedStartAt).format('YYYY-MM-DD HH:mm:ss') : '-'}</Descriptions.Item>
              <Descriptions.Item label="估算可信度">{etaConfidenceLabel(selectedTask.etaConfidence)}</Descriptions.Item>
              <Descriptions.Item label="失败原因" span={2}>{selectedTask.errorMessage || '-'}</Descriptions.Item>
            </Descriptions>
            <div className="section-title">状态时间线</div>
            <Timeline
              items={(events.data || []).map((event) => ({
                color: event.eventType === 'FAILED' ? 'red' : event.eventType === 'SUCCEEDED' ? 'green' : 'blue',
                children: <Space direction="vertical" size={0}><strong>{event.eventType}</strong><span>{dayjs(event.createdAt).format('YYYY-MM-DD HH:mm:ss')}</span><code>{event.payloadJson}</code></Space>,
              }))}
            />
          </>
        )}
      </Drawer>
    </>
  )
}

function formatDuration(seconds?: number) {
  if (seconds === undefined || seconds === null) return '-'
  if (seconds < 60) return `${seconds}秒`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}分钟`
  return `${Math.floor(minutes / 60)}小时${minutes % 60}分钟`
}
