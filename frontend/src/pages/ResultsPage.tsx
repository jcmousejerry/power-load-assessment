import { EyeOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Card, Descriptions, Drawer, Empty, Select, Space, Spin, Table, Typography } from 'antd'
import type { EChartsCoreOption as EChartsOption } from 'echarts/core'
import dayjs from 'dayjs'
import { useEffect, useMemo, useState } from 'react'
import { getResult, listDatasets, listPipelines, listResults } from '../api/services'
import Chart from '../components/Chart'
import PageHeader from '../components/PageHeader'
import type { AnalysisResult } from '../types'
import { taskTypeLabel, taskTypeOptions } from '../taskMeta'

export default function ResultsPage() {
  const datasets = useQuery({ queryKey: ['datasets'], queryFn: listDatasets })
  const [datasetFilter, setDatasetFilter] = useState<number>()
  const [pipelineFilter, setPipelineFilter] = useState<number>()
  const pipelines = useQuery({
    queryKey: ['pipelines', datasetFilter],
    queryFn: () => listPipelines(datasetFilter),
    enabled: Boolean(datasetFilter),
  })
  const results = useQuery({
    queryKey: ['results', datasetFilter, pipelineFilter],
    queryFn: () => listResults({ datasetId: datasetFilter, pipelineId: pipelineFilter }),
    enabled: Boolean(datasetFilter) && pipelineFilter !== undefined,
    refetchInterval: 5000,
  })
  const [typeFilter, setTypeFilter] = useState<string>()
  const [selected, setSelected] = useState<AnalysisResult | null>(null)
  const filtered = (results.data || []).filter((result) => !typeFilter || result.resultType === typeFilter)

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

  return (
    <>
      <PageHeader
        title="分析结果"
        description="查看数据质量、用户特征、聚类、预测、基线和潜力结果。"
        extra={<Select allowClear placeholder="筛选结果类型" style={{ width: 230 }} value={typeFilter} onChange={setTypeFilter} options={taskTypeOptions.map(({ value, label }) => ({ value, label }))} />}
      />
      <Alert
        showIcon
        type="info"
        style={{ marginBottom: 16 }}
        message="结果类型说明"
        description={taskTypeOptions.map((item) => `${item.label}：${item.description}`).join('；')}
      />
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap>
          <span>当前数据源</span>
          <Select
            style={{ width: 260 }}
            placeholder="选择负荷数据源"
            value={datasetFilter}
            onChange={(value) => { setDatasetFilter(value); setPipelineFilter(undefined); setSelected(null) }}
            options={(datasets.data || []).map((dataset) => ({ value: dataset.id, label: dataset.name }))}
          />
          <span>当前流水线</span>
          <Select
            style={{ width: 300 }}
            placeholder="选择分析流水线"
            loading={pipelines.isLoading}
            value={pipelineFilter}
            onChange={(value) => { setPipelineFilter(value); setSelected(null) }}
            options={[
              ...(pipelines.data || []).map((pipeline) => ({
                value: pipeline.id,
                label: `${pipeline.pipelineName} · ${dayjs(pipeline.createdAt).format('MM-DD HH:mm')}`,
              })),
              { value: 0, label: '历史未分组结果' },
            ]}
          />
        </Space>
      </Card>
      <Card className="content-card">
        <Table<AnalysisResult>
          rowKey="id"
          loading={results.isLoading}
          dataSource={filtered}
          columns={[
            { title: '结果编号', dataIndex: 'id', width: 110 },
            { title: '任务编号', dataIndex: 'taskId', width: 110 },
            { title: '结果类型', dataIndex: 'resultType', width: 190, render: (value) => taskTypeLabel(value) },
            { title: '算法版本', dataIndex: 'algorithmVersion', width: 140 },
            { title: '生成时间', dataIndex: 'createdAt', render: (value) => dayjs(value).format('YYYY-MM-DD HH:mm:ss') },
            { title: '结果文件', dataIndex: 'artifactObjectKey', ellipsis: true },
            { title: '操作', width: 100, render: (_, result) => <Button type="link" icon={<EyeOutlined />} onClick={() => setSelected(result)}>查看</Button> },
          ]}
        />
      </Card>
      <Drawer title={`${taskTypeLabel(selected?.resultType)}结果详情`} width="76vw" open={Boolean(selected)} onClose={() => setSelected(null)}>
        {selected && <ResultDetail result={selected} />}
      </Drawer>
    </>
  )
}

function ResultDetail({ result }: { result: AnalysisResult }) {
  const detail = useQuery({
    queryKey: ['result-detail', result.id],
    queryFn: () => getResult(result.id),
  })
  const currentResult = detail.data || result
  const summary = useMemo(() => {
    try { return JSON.parse(currentResult.summaryJson) as Record<string, any> } catch { return {} }
  }, [currentResult.summaryJson])
  const option = useMemo(
    () => buildChartOption(currentResult.resultType, summary),
    [currentResult.resultType, summary],
  )

  if (detail.isLoading) {
    return <Spin tip="正在读取结果详情" />
  }

  return (
    <Space direction="vertical" size={20} style={{ width: '100%' }}>
      <Descriptions bordered size="small" column={3}>
        <Descriptions.Item label="任务编号">{currentResult.taskId}</Descriptions.Item>
        <Descriptions.Item label="结果类型">{taskTypeLabel(currentResult.resultType)}</Descriptions.Item>
        <Descriptions.Item label="算法版本">{currentResult.algorithmVersion}</Descriptions.Item>
      </Descriptions>
      {option ? <Card title="结果曲线"><Chart option={option} height={430} /></Card> : <Empty description="该结果以指标和明细形式展示" />}
      <Card title="完整结果摘要"><Typography.Paragraph><pre className="json-viewer">{JSON.stringify(summary, null, 2)}</pre></Typography.Paragraph></Card>
    </Space>
  )
}

function buildChartOption(type: string, summary: Record<string, any>): EChartsOption | null {
  const base = { tooltip: { trigger: 'axis' as const }, legend: { top: 0 }, grid: { left: 55, right: 25, top: 48, bottom: 70 }, dataZoom: [{ type: 'inside' as const }, { type: 'slider' as const, bottom: 15 }], yAxis: { type: 'value' as const, name: 'kW' } }
  if (type === 'FORECAST' && summary.forecast) {
    return { ...base, xAxis: { type: 'category', data: summary.forecast.map((point: any) => point.timestamp) }, series: [
      { name: 'P10', type: 'line', data: summary.forecast.map((point: any) => point.p10), lineStyle: { opacity: 0.45 }, symbol: 'none' },
      { name: 'P50预测', type: 'line', data: summary.forecast.map((point: any) => point.p50), symbol: 'none', lineStyle: { width: 3, color: '#0f6b78' } },
      { name: 'P90', type: 'line', data: summary.forecast.map((point: any) => point.p90), lineStyle: { opacity: 0.45 }, symbol: 'none' },
    ] }
  }
  if (type === 'POTENTIAL' && summary.points) {
    return { ...base, xAxis: { type: 'category', data: summary.points.map((point: any) => point.timestamp) }, series: [
      { name: '预测负荷', type: 'line', data: summary.points.map((point: any) => point.forecastKw), symbol: 'none' },
      { name: '基线', type: 'line', data: summary.points.map((point: any) => point.baselineKw), symbol: 'none' },
      { name: '削峰潜力', type: 'bar', data: summary.points.map((point: any) => point.downKw), itemStyle: { color: '#f08c46' } },
      { name: '填谷潜力', type: 'bar', data: summary.points.map((point: any) => point.upKw), itemStyle: { color: '#2f9e88' } },
    ] }
  }
  if (type === 'BASELINE' && summary.curves) {
    const curves = summary.curves as Record<string, number[]>
    const firstCurve = Object.values(curves)[0] || []
    return { ...base, xAxis: { type: 'category', data: firstCurve.map((_, index) => index + 1) }, series: Object.entries(curves).map(([name, values]) => ({ name, type: 'line', data: values, symbol: 'none' })) }
  }
  if (type === 'CLUSTER' && summary.clusters) {
    const interval = Number(summary.intervalMinutes || 15)
    return { ...base, xAxis: { type: 'category', data: summary.clusters[0]?.representativeCurve.map((_: unknown, index: number) => formatTimeSlot(index, interval)) || [] }, series: summary.clusters.map((cluster: any) => ({ name: `簇${cluster.clusterId}`, type: 'line', data: cluster.representativeCurve, symbol: 'none' })) }
  }
  if (type === 'PROFILE' && summary.preview) {
    return { ...base, xAxis: { type: 'category', data: summary.preview.map((point: any) => point.timestamp) }, series: [{ name: '聚合负荷', type: 'line', data: summary.preview.map((point: any) => point.value), symbol: 'none', areaStyle: { opacity: 0.12 } }] }
  }
  return null
}

function formatTimeSlot(index: number, intervalMinutes: number) {
  const totalMinutes = index * intervalMinutes
  return `${String(Math.floor(totalMinutes / 60)).padStart(2, '0')}:${String(totalMinutes % 60).padStart(2, '0')}`
}
