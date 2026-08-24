import { Tag } from 'antd'

const statusMap: Record<string, { color: string; text: string }> = {
  WAITING_DEPENDENCY: { color: 'blue', text: '等待上游' },
  UPLOADED: { color: 'blue', text: '已上传' },
  READY: { color: 'green', text: '可分析' },
  QUEUED: { color: 'gold', text: '排队中' },
  RUNNING: { color: 'processing', text: '运行中' },
  CANCEL_REQUESTED: { color: 'orange', text: '正在取消' },
  CANCELLED: { color: 'default', text: '已取消' },
  SUCCEEDED: { color: 'success', text: '成功' },
  FAILED: { color: 'error', text: '失败' },
  REJECTED: { color: 'error', text: '已拒绝' },
}

export default function StatusTag({ status }: { status: string }) {
  const config = statusMap[status] || { color: 'default', text: status }
  return <Tag color={config.color}>{config.text}</Tag>
}
