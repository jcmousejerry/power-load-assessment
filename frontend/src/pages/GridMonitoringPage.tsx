import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert, Badge, Button, Card, Col, Descriptions, Divider, message, Modal, Popconfirm,
  Progress, Row, Select, Space, Statistic, Table, Tag, Typography,
} from 'antd'
import type { EChartsCoreOption as EChartsOption } from 'echarts/core'
import dayjs from 'dayjs'
import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  listGridAlerts,
  listGridMetricHistory,
  listGridMetrics,
  listGridTransformers,
  listLatestGridTelemetry,
  listMonitoredGridTransformers,
  startGridTransformerMonitoring,
  stopGridTransformerMonitoring,
} from '../api/services'
import Chart from '../components/Chart'
import PageHeader from '../components/PageHeader'
import type {
  GridTelemetrySnapshot, GridTransformer, GridTransformerMetric, GridTransformerMetricHistory,
} from '../types'

const statusMeta: Record<string, { label: string; color: string }> = {
  NORMAL: { label: '正常', color: 'success' },
  WATCH: { label: '关注', color: 'warning' },
  HISTORICAL_ANOMALY: { label: '异常升高', color: 'orange' },
  OVERLOAD: { label: '容量过载', color: 'error' },
}

type RuntimeRow = GridTransformer & {
  telemetry?: GridTelemetrySnapshot
  metric?: GridTransformerMetric
}

export default function GridMonitoringPage() {
  const navigate = useNavigate()
  const [transformerFilter, setTransformerFilter] = useState<string>()
  const [alertTypeFilter, setAlertTypeFilter] = useState<string>()
  const [transformerToAdd, setTransformerToAdd] = useState<string>()
  const [trendTransformerCode, setTrendTransformerCode] = useState<string>()
  const [historyRangeMinutes, setHistoryRangeMinutes] = useState(60)
  const [alertPage, setAlertPage] = useState(1)
  const [alertPageSize, setAlertPageSize] = useState(10)
  const queryClient = useQueryClient()
  const transformers = useQuery({ queryKey: ['grid-transformers'], queryFn: listGridTransformers })
  const monitoredTransformers = useQuery({
    queryKey: ['grid-monitored-transformers'],
    queryFn: listMonitoredGridTransformers,
    refetchInterval: 3000,
  })
  const telemetry = useQuery({
    queryKey: ['grid-telemetry-latest'],
    queryFn: listLatestGridTelemetry,
    refetchInterval: 1000,
  })
  const metrics = useQuery({
    queryKey: ['grid-metrics'],
    queryFn: listGridMetrics,
    refetchInterval: 2000,
  })
  const alerts = useQuery({
    queryKey: ['grid-alerts', transformerFilter, alertTypeFilter],
    queryFn: () => listGridAlerts({ transformerCode: transformerFilter, alertType: alertTypeFilter, limit: 50 }),
    refetchInterval: 3000,
  })
  const metricHistory = useQuery({
    queryKey: ['grid-metric-history', trendTransformerCode, historyRangeMinutes],
    queryFn: () => listGridMetricHistory(trendTransformerCode!, historyRangeMinutes),
    enabled: Boolean(trendTransformerCode),
    refetchInterval: trendTransformerCode ? 2000 : false,
  })

  const transformerByCode = useMemo(
    () => new Map((transformers.data || []).map((item) => [item.transformerCode, item])),
    [transformers.data],
  )
  const monitoredCodes = useMemo(
    () => new Set((monitoredTransformers.data || []).map((item) => item.transformerCode)),
    [monitoredTransformers.data],
  )
  const telemetryByCode = useMemo(
    () => new Map((telemetry.data || []).map((item) => [item.transformerCode, item])),
    [telemetry.data],
  )
  const metricByCode = useMemo(
    () => new Map((metrics.data || []).map((item) => [item.transformerCode, item])),
    [metrics.data],
  )
  const runtimeRows = useMemo<RuntimeRow[]>(
    () => (monitoredTransformers.data || []).map((item) => ({
      ...item,
      telemetry: telemetryByCode.get(item.transformerCode),
      metric: metricByCode.get(item.transformerCode),
    })),
    [metricByCode, monitoredTransformers.data, telemetryByCode],
  )
  const availableTransformers = (transformers.data || []).filter((item) => !monitoredCodes.has(item.transformerCode))
  const refreshMonitoring = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['grid-transformers'] }),
      queryClient.invalidateQueries({ queryKey: ['grid-monitored-transformers'] }),
      queryClient.invalidateQueries({ queryKey: ['grid-telemetry-latest'] }),
      queryClient.invalidateQueries({ queryKey: ['grid-metrics'] }),
      queryClient.invalidateQueries({ queryKey: ['grid-alerts'] }),
    ])
  }
  const addMonitoring = useMutation({
    mutationFn: startGridTransformerMonitoring,
    onSuccess: async () => {
      setTransformerToAdd(undefined)
      await refreshMonitoring()
      message.success('已加入实时监控')
    },
  })
  const removeMonitoring = useMutation({
    mutationFn: stopGridTransformerMonitoring,
    onSuccess: async (_, transformerCode) => {
      if (transformerFilter === transformerCode) setTransformerFilter(undefined)
      if (trendTransformerCode === transformerCode) setTrendTransformerCode(undefined)
      await refreshMonitoring()
      message.success('已取消实时监控，并清除该变压器的个人预警记录')
    },
  })
  const metricList = metrics.data || []
  const abnormalCount = metricList.filter((item) => ['HISTORICAL_ANOMALY', 'OVERLOAD'].includes(item.status)).length
  const overloadCount = metricList.filter((item) => item.status === 'OVERLOAD').length
  const trendTransformer = trendTransformerCode ? transformerByCode.get(trendTransformerCode) : undefined
  const trendTelemetry = trendTransformerCode ? telemetryByCode.get(trendTransformerCode) : undefined
  const trendMetric = trendTransformerCode ? metricByCode.get(trendTransformerCode) : undefined
  const trendHistory = metricHistory.data || []
  const trendCoverage = trendHistory.length
    ? `${dayjs(trendHistory[0].windowEnd).format('MM-DD HH:mm:ss')} ～ ${dayjs(trendHistory[trendHistory.length - 1].windowEnd).format('MM-DD HH:mm:ss')}`
    : '当前范围内暂无Flink窗口数据'
  const trendOption = useMemo(
    () => buildTrendOption(trendHistory, historyRangeMinutes, trendTelemetry, trendTransformer),
    [historyRangeMinutes, trendHistory, trendTelemetry, trendTransformer],
  )

  return (
    <>
      <PageHeader
        title="变压器实时预警"
        description="查看每台设备当前是否正常。发现负荷偏高或过载后，可以直接分析影响，也可以让AI给出处理建议。"
      />
      <Alert
        showIcon
        type="info"
        message={(
          <Space wrap size="middle">
            <Badge status="processing" text="实时采集运行中" />
            <span>采集刷新：2秒</span><span>Flink窗口：10秒</span><span>页面刷新：1～2秒</span>
          </Space>
        )}
        description="发现异常后，请使用设备右侧的“影响分析”或“AI帮我处理”，系统会自动带入设备信息。技术人员仍可在表格中查看采集值、窗口结果和历史参考线。"
        style={{ marginBottom: 18 }}
      />
      <Card title="监控对象管理" className="content-card" style={{ marginBottom: 18 }}>
        <Space wrap style={{ marginBottom: 16 }}>
          <Select
            showSearch allowClear placeholder="从设备库选择变压器" value={transformerToAdd}
            onChange={setTransformerToAdd} style={{ width: 320 }} optionFilterProp="label"
            options={availableTransformers.map((item) => ({
              value: item.transformerCode,
              label: `${item.transformerCode} · ${item.transformerName} · ${item.areaName}`,
            }))}
          />
          <Button
            type="primary" disabled={!transformerToAdd} loading={addMonitoring.isPending}
            onClick={() => transformerToAdd && addMonitoring.mutate(transformerToAdd)}
          >添加监控</Button>
          <Typography.Text type="secondary">
            设备库共 {transformers.data?.length || 0} 台，已监控 {monitoredTransformers.data?.length || 0} 台
          </Typography.Text>
        </Space>
        <Table
          size="small" rowKey="transformerCode" loading={monitoredTransformers.isLoading}
          pagination={false} dataSource={monitoredTransformers.data || []} scroll={{ x: 900 }}
          columns={[
            { title: '设备编号', dataIndex: 'transformerCode', width: 120 },
            { title: '变压器', dataIndex: 'transformerName', width: 240 },
            { title: '区域', dataIndex: 'areaName', width: 160 },
            { title: '额定容量', dataIndex: 'ratedCapacityKw', width: 140, render: (value) => `${Number(value).toFixed(0)} kW` },
            {
              title: '操作', width: 200,
              render: (_, item) => (
                <Space>
                  <Button type="link" onClick={() => setTrendTransformerCode(item.transformerCode)}>负荷趋势</Button>
                  <Popconfirm
                    title="取消实时监控？" description="设备档案和历史数据不会被删除。"
                    onConfirm={() => removeMonitoring.mutate(item.transformerCode)}
                  ><Button danger type="link">删除监控</Button></Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      </Card>
      <Row gutter={[18, 18]}>
        <Col xs={24} md={8}>
          <Card className="metric-card"><Statistic title="监控变压器" value={monitoredTransformers.data?.length || 0} suffix="台" /></Card>
        </Col>
        <Col xs={24} md={8}>
          <Card className="metric-card"><Statistic title="当前异常" value={abnormalCount} suffix="台" valueStyle={{ color: abnormalCount ? '#d46b08' : undefined }} /></Card>
        </Col>
        <Col xs={24} md={8}>
          <Card className="metric-card"><Statistic title="当前过载" value={overloadCount} suffix="台" valueStyle={{ color: overloadCount ? '#cf1322' : undefined }} /></Card>
        </Col>
        <Col span={24}>
          <Card
            title="实时运行状态" className="content-card"
            extra={<Badge status="processing" text={`正在监测 ${runtimeRows.length} 台设备`} />}
          >
            <Alert
              type="info" showIcon
              message="Flink状态按当前窗口即时显示；连续异常达到3个窗口后生成预警，异常持续时每6个窗口再次提醒。"
              style={{ marginBottom: 12 }}
            />
            <Table
              className="grid-runtime-table" rowKey="transformerCode" size="small" bordered
              loading={metrics.isLoading || transformers.isLoading || telemetry.isLoading}
              pagination={false} dataSource={runtimeRows} scroll={{ x: 1960 }}
              columns={[
                {
                  title: '变压器', dataIndex: 'transformerCode', width: 260, fixed: 'left',
                  render: (code, row) => (
                    <div className="runtime-transformer-cell">
                      <Typography.Text strong>{row.transformerName || code}</Typography.Text>
                      <Typography.Text type="secondary">{code} · {row.areaName}</Typography.Text>
                    </div>
                  ),
                },
                {
                  title: '采集端最新负荷', width: 180,
                  render: (_, row) => row.telemetry ? (
                    <div className="runtime-value-cell">
                      <Typography.Text strong>{Number(row.telemetry.totalLoadKw).toFixed(1)} kW</Typography.Text>
                      <Typography.Text type="secondary">采集于 {dayjs(row.telemetry.sampleTime).format('HH:mm:ss')}</Typography.Text>
                    </div>
                  ) : <Typography.Text type="secondary">等待采集...</Typography.Text>,
                },
                {
                  title: '实时电表明细', width: 300,
                  render: (_, row) => row.telemetry ? (
                    <Space size={[4, 4]} wrap>
                      {Object.entries(row.telemetry.meterLoadsKw).map(([meterId, value]) => (
                        <Tag key={meterId}>{meterId.split('-').pop()} {Number(value).toFixed(1)} kW</Tag>
                      ))}
                    </Space>
                  ) : '-',
                },
                {
                  title: 'Flink窗口负荷', width: 180,
                  render: (_, row) => row.metric ? (
                    <div className="runtime-value-cell">
                      <Typography.Text strong>{Number(row.metric.currentLoadKw).toFixed(1)} kW</Typography.Text>
                      <Typography.Text type="secondary">10秒窗口聚合结果</Typography.Text>
                    </div>
                  ) : <Typography.Text type="secondary">等待首个窗口...</Typography.Text>,
                },
                { title: '额定容量', width: 140, render: (_, row) => `${Number(row.ratedCapacityKw).toFixed(0)} kW` },
                {
                  title: '负载率', width: 220,
                  render: (_, row) => row.metric
                    ? <Progress percent={Math.round(Number(row.metric.loadRate) * 100)} status={Number(row.metric.loadRate) > 1 ? 'exception' : 'active'} size="small" />
                    : '-',
                },
                {
                  title: 'Spark历史P95', width: 160,
                  render: (_, row) => row.metric?.historicalUpperKw == null
                    ? <Typography.Text type="secondary">等待阈值</Typography.Text>
                    : `${Number(row.metric.historicalUpperKw).toFixed(1)} kW`,
                },
                { title: 'Flink状态', width: 120, render: (_, row) => row.metric ? statusTag(row.metric.status) : <Tag>等待</Tag> },
                { title: '连续异常窗口', width: 140, align: 'center', render: (_, row) => row.metric?.consecutiveAbnormalWindows ?? '-' },
                {
                  title: 'Flink窗口结束', width: 180,
                  render: (_, row) => row.metric ? dayjs(row.metric.windowEnd).format('YYYY-MM-DD HH:mm:ss') : '-',
                },
                {
                  title: '下一步', width: 280, fixed: 'right',
                  render: (_, row) => <Space>
                    <Button type="link" onClick={() => setTrendTransformerCode(row.transformerCode)}>看趋势</Button>
                    <Button onClick={() => navigate(`/maintenance-planning?device=${row.transformerCode}`)}>影响分析</Button>
                    <Button type="primary" onClick={() => navigate(`/planning-agent?device=${row.transformerCode}`)}>AI帮我处理</Button>
                  </Space>,
                },
              ]}
            />
          </Card>
        </Col>
        <Col span={24}>
          <Card
            title="预警记录" className="content-card"
            extra={(
              <>
                <Select
                  allowClear placeholder="全部变压器" value={transformerFilter}
                  onChange={(value) => { setTransformerFilter(value); setAlertPage(1) }}
                  style={{ width: 180, marginRight: 10 }}
                  options={(monitoredTransformers.data || []).map((item) => ({ value: item.transformerCode, label: `${item.transformerCode} ${item.transformerName}` }))}
                />
                <Select
                  allowClear placeholder="全部类型" value={alertTypeFilter}
                  onChange={(value) => { setAlertTypeFilter(value); setAlertPage(1) }}
                  style={{ width: 150 }}
                  options={[{ value: 'HISTORICAL_ANOMALY', label: '历史异常升高' }, { value: 'OVERLOAD', label: '容量过载' }]}
                />
              </>
            )}
          >
            <Table
              rowKey="eventId" loading={alerts.isLoading} dataSource={alerts.data || []}
              pagination={{
                current: alertPage,
                pageSize: alertPageSize,
                showSizeChanger: true,
                pageSizeOptions: [10, 20, 50],
                showTotal: (total) => `共 ${total} 条预警`,
                onChange: (page, pageSize) => {
                  setAlertPage(pageSize === alertPageSize ? page : 1)
                  setAlertPageSize(pageSize)
                },
              }}
              scroll={{ x: 1250 }}
              columns={[
                { title: '发生时间', dataIndex: 'eventTime', width: 180, render: (value) => dayjs(value).format('YYYY-MM-DD HH:mm:ss') },
                { title: '变压器', dataIndex: 'transformerCode', width: 240, render: (code) => `${code} · ${transformerByCode.get(code)?.transformerName || ''}` },
                { title: '预警类型', dataIndex: 'alertType', width: 140, render: statusTag },
                { title: '实时负荷', dataIndex: 'currentLoadKw', width: 130, render: (value) => `${Number(value).toFixed(1)} kW` },
                { title: '额定容量', dataIndex: 'ratedCapacityKw', width: 130, render: (value) => `${Number(value).toFixed(0)} kW` },
                { title: '历史P95', dataIndex: 'historicalUpperKw', width: 130, render: (value) => value == null ? '-' : `${Number(value).toFixed(1)} kW` },
                { title: '负载率', dataIndex: 'loadRate', width: 110, render: (value) => `${(Number(value) * 100).toFixed(1)}%` },
                { title: '连续窗口', dataIndex: 'consecutiveWindows', width: 110 },
                {
                  title: '处理', width: 220, fixed: 'right',
                  render: (_, row) => <Space>
                    <Button onClick={() => navigate(`/maintenance-planning?device=${row.transformerCode}`)}>影响分析</Button>
                    <Button type="primary" onClick={() => navigate(`/planning-agent?device=${row.transformerCode}`)}>AI分析</Button>
                  </Space>,
                },
              ]}
            />
          </Card>
        </Col>
      </Row>

      <Modal
        open={Boolean(trendTransformerCode)}
        title={trendTransformer ? `${trendTransformer.transformerCode} · ${trendTransformer.transformerName} 实时负荷监测` : '实时负荷监测'}
        width={1120} footer={null} destroyOnClose onCancel={() => setTrendTransformerCode(undefined)}
      >
        <Alert
          type="info" showIcon
          message="蓝线是Flink窗口聚合负荷，红色虚线是额定容量，橙色虚线是Spark历史P95，绿色点是采集端最新原始负荷。"
          style={{ marginBottom: 16 }}
        />
        <Row gutter={[12, 12]}>
          <Col xs={12} md={6}><Card size="small"><Statistic title="采集端最新" value={trendTelemetry?.totalLoadKw} precision={1} suffix="kW" /></Card></Col>
          <Col xs={12} md={6}><Card size="small"><Statistic title="Flink窗口负荷" value={trendMetric?.currentLoadKw} precision={1} suffix="kW" /></Card></Col>
          <Col xs={12} md={6}><Card size="small"><Statistic title="额定容量" value={trendTransformer?.ratedCapacityKw} precision={0} suffix="kW" /></Card></Col>
          <Col xs={12} md={6}><Card size="small"><Statistic title="Spark历史P95" value={trendMetric?.historicalUpperKw} precision={1} suffix="kW" /></Card></Col>
        </Row>
        <Descriptions size="small" column={4} bordered style={{ marginTop: 14 }}>
          <Descriptions.Item label="Flink状态">{trendMetric ? statusTag(trendMetric.status) : '等待首个窗口'}</Descriptions.Item>
          <Descriptions.Item label="负载率">{trendMetric ? `${(Number(trendMetric.loadRate) * 100).toFixed(1)}%` : '-'}</Descriptions.Item>
          <Descriptions.Item label="采集时间">{trendTelemetry ? dayjs(trendTelemetry.sampleTime).format('HH:mm:ss') : '-'}</Descriptions.Item>
          <Descriptions.Item label="窗口结束">{trendMetric ? dayjs(trendMetric.windowEnd).format('HH:mm:ss') : '-'}</Descriptions.Item>
        </Descriptions>
        <Divider orientation="left">实时电表采集值</Divider>
        <Space wrap>
          {trendTelemetry
            ? Object.entries(trendTelemetry.meterLoadsKw).map(([meterId, value]) => <Tag color="cyan" key={meterId}>{meterId}：{Number(value).toFixed(1)} kW</Tag>)
            : <Typography.Text type="secondary">等待采集数据...</Typography.Text>}
        </Space>
        <Divider orientation="left">
          <Space>
            <span>负荷趋势</span>
            <Select
              size="small" value={historyRangeMinutes} onChange={setHistoryRangeMinutes}
              options={[
                { value: 15, label: '最近15分钟' },
                { value: 60, label: '最近1小时' },
                { value: 360, label: '最近6小时' },
              ]}
            />
            <Typography.Text type="secondary">
              固定展示最近{historyRangeMinutes >= 60 ? `${historyRangeMinutes / 60}小时` : `${historyRangeMinutes}分钟`}
              ，当前有{trendHistory.length}个窗口；实际数据：{trendCoverage}
            </Typography.Text>
          </Space>
        </Divider>
        <Chart option={trendOption} height={360} />
        <Divider orientation="left">最近Flink窗口明细</Divider>
        <Table
          size="small" rowKey="id" loading={metricHistory.isLoading} pagination={false}
          dataSource={[...(metricHistory.data || [])].reverse().slice(0, 8)}
          columns={[
            { title: '窗口结束', dataIndex: 'windowEnd', render: (value) => dayjs(value).format('YYYY-MM-DD HH:mm:ss') },
            { title: 'Flink负荷', dataIndex: 'currentLoadKw', render: (value) => `${Number(value).toFixed(1)} kW` },
            { title: '负载率', dataIndex: 'loadRate', render: (value) => `${(Number(value) * 100).toFixed(1)}%` },
            { title: 'Spark P95', dataIndex: 'historicalUpperKw', render: (value) => value == null ? '-' : `${Number(value).toFixed(1)} kW` },
            { title: '状态', dataIndex: 'status', render: statusTag },
          ]}
        />
      </Modal>
    </>
  )
}

function buildTrendOption(
  history: GridTransformerMetricHistory[], rangeMinutes: number,
  telemetry?: GridTelemetrySnapshot, transformer?: GridTransformer,
): EChartsOption {
  const latestSampleTime = telemetry ? dayjs(telemetry.sampleTime).valueOf() : 0
  const rangeEnd = Math.max(Date.now(), latestSampleTime)
  const rangeStart = rangeEnd - rangeMinutes * 60 * 1000
  const flinkLoads = history.map((item) => [dayjs(item.windowEnd).valueOf(), Number(item.currentLoadKw)])
  const latestHistory = history.length ? history[history.length - 1] : undefined
  const capacityValue = Number(transformer?.ratedCapacityKw || latestHistory?.ratedCapacityKw)
  const capacity = Number.isFinite(capacityValue)
    ? [[rangeStart, capacityValue], [rangeEnd, capacityValue]]
    : []
  const p95 = history
    .filter((item) => item.historicalUpperKw != null)
    .map((item) => [dayjs(item.windowEnd).valueOf(), Number(item.historicalUpperKw)])
  const rawLatest = telemetry ? [[latestSampleTime, Number(telemetry.totalLoadKw)]] : []
  return {
    animationDuration: 350,
    color: ['#1677ff', '#ff4d4f', '#fa8c16', '#13a8a8'],
    tooltip: { trigger: 'axis' },
    legend: { top: 4, data: ['Flink窗口负荷', '额定容量', 'Spark历史P95', '采集端最新值'] },
    grid: { left: 62, right: 28, top: 52, bottom: 58 },
    dataZoom: [{ type: 'inside' }, { type: 'slider', height: 18, bottom: 10 }],
    xAxis: {
      type: 'time', min: rangeStart, max: rangeEnd,
      axisLabel: { formatter: (value: number) => dayjs(value).format(rangeMinutes <= 15 ? 'HH:mm:ss' : 'HH:mm') },
    },
    yAxis: { type: 'value', name: '负荷（kW）', scale: true, splitLine: { lineStyle: { color: '#edf2f7' } } },
    series: [
      { name: 'Flink窗口负荷', type: 'line', smooth: true, showSymbol: false, lineStyle: { width: 3 }, data: flinkLoads },
      { name: '额定容量', type: 'line', symbol: 'none', lineStyle: { type: 'dashed', width: 2 }, data: capacity },
      { name: 'Spark历史P95', type: 'line', symbol: 'none', lineStyle: { type: 'dashed', width: 2 }, data: p95 },
      { name: '采集端最新值', type: 'line', connectNulls: false, symbolSize: 13, lineStyle: { width: 0 }, data: rawLatest },
    ],
  }
}

function statusTag(status: string) {
  const meta = statusMeta[status] || { label: status, color: 'default' }
  return <Tag color={meta.color}>{meta.label}</Tag>
}
