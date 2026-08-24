import { useQuery } from '@tanstack/react-query'
import { Card, Col, Progress, Row, Statistic, Table, Typography } from 'antd'
import { DatabaseOutlined, FileDoneOutlined, LoadingOutlined, ThunderboltOutlined } from '@ant-design/icons'
import { listDatasets, listResults, listTasks } from '../api/services'
import PageHeader from '../components/PageHeader'
import StatusTag from '../components/StatusTag'
import { taskStageLabel, taskTypeLabel } from '../taskMeta'

export default function DashboardPage() {
  const datasets = useQuery({ queryKey: ['datasets'], queryFn: listDatasets })
  const tasks = useQuery({ queryKey: ['tasks'], queryFn: () => listTasks(), refetchInterval: 4000 })
  const results = useQuery({ queryKey: ['results'], queryFn: () => listResults() })
  const taskList = tasks.data || []
  const activeCount = taskList.filter((task) => ['WAITING_DEPENDENCY', 'QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(task.status)).length
  const successCount = taskList.filter((task) => task.status === 'SUCCEEDED').length

  return (
    <>
      <PageHeader title="运行总览" description="查看数据、任务和分析结果的当前状态。" />
      <Row gutter={[18, 18]}>
        <Col xs={24} sm={12} xl={6}>
          <Card className="metric-card">
            <Statistic title="数据集" value={datasets.data?.length || 0} prefix={<DatabaseOutlined />} />
          </Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card className="metric-card">
            <Statistic title="全部任务" value={taskList.length} prefix={<ThunderboltOutlined />} />
          </Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card className="metric-card">
            <Statistic title="正在处理" value={activeCount} prefix={<LoadingOutlined />} />
          </Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card className="metric-card">
            <Statistic title="有效结果" value={results.data?.length || successCount} prefix={<FileDoneOutlined />} />
          </Card>
        </Col>
        <Col span={24}>
          <Card title="最近任务" className="content-card">
            <Table
              rowKey="id"
              loading={tasks.isLoading}
              pagination={false}
              dataSource={taskList.slice(0, 6)}
              columns={[
                { title: '任务', dataIndex: 'taskName' },
                { title: '类型', dataIndex: 'taskType', width: 180, render: (value) => taskTypeLabel(value) },
                { title: '状态', dataIndex: 'status', width: 110, render: (status) => <StatusTag status={status} /> },
                {
                  title: '进度',
                  dataIndex: 'progress',
                  width: 220,
                  render: (value) => <Progress percent={Number(value)} size="small" />,
                },
                { title: '阶段', dataIndex: 'stage', render: (value) => <Typography.Text type="secondary">{taskStageLabel(value)}</Typography.Text> },
              ]}
            />
          </Card>
        </Col>
      </Row>
    </>
  )
}
