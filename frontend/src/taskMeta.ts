export const taskTypeOptions = [
  { value: 'PROFILE', label: '数据剖析与质量检查', description: '检查完整性、重复值、异常行、采样间隔和质量评分' },
  { value: 'FEATURE', label: '用户特征提取', description: '提取峰谷、负荷率、用电量和爬坡等用户特征' },
  { value: 'CLUSTER', label: '用户聚类', description: '按负荷曲线形态将用户划分为若干集群' },
  { value: 'FORECAST', label: '集群负荷预测', description: '对聚类后的单个集群预测未来负荷及区间' },
  { value: 'BASELINE', label: '集群负荷基线', description: '对聚类后的单个集群计算多策略基线' },
  { value: 'POTENTIAL', label: '集群调节潜力', description: '结合该集群的预测和基线计算削峰填谷潜力' },
] as const

export function taskTypeLabel(value?: string) {
  return taskTypeOptions.find((item) => item.value === value)?.label || value || '-'
}

const stageLabels: Record<string, string> = {
  WAITING_PREREQUISITE: '等待上游任务',
  WAITING_RESOURCE: '等待计算资源',
  PREPARING_DATA: '准备数据',
  ALGORITHM_RUNNING: '算法计算中',
  COMPLETED: '已完成',
  FAILED: '执行失败',
  UPSTREAM_FAILED: '上游失败，未执行',
  CANCELLED: '已取消',
  CANCELLED_BEFORE_START: '启动前已取消',
}

export function taskStageLabel(value?: string) {
  return value ? stageLabels[value] || value : '-'
}

export function etaConfidenceLabel(value?: string) {
  return ({ LOW: '较低', MEDIUM: '中等', HIGH: '较高' } as Record<string, string>)[value || ''] || '-'
}
