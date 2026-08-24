import * as echarts from 'echarts/core'
import type { EChartsCoreOption } from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useEffect, useMemo, useRef } from 'react'
import type { PlanningTopology, SimulationPoint, TransferScenario } from '../types'

echarts.use([GraphChart, LegendComponent, TooltipComponent, CanvasRenderer])

type Props = {
  topology: PlanningTopology
  scenario?: TransferScenario
  frame?: SimulationPoint[]
  height?: number
  controlState?: {
    sourceSwitchState: 'OPEN' | 'CLOSED'
    tieSwitchState: 'OPEN' | 'CLOSED'
    supplyState: 'NORMAL' | 'INTERRUPTED' | 'BACKUP'
    transferred: boolean
  }
}

export default function GridTopologyChart({ topology, scenario, frame = [], height = 520, controlState }: Props) {
  const container = useRef<HTMLDivElement>(null)
  const option = useMemo(() => buildOption(topology, scenario, frame, controlState), [topology, scenario, frame, controlState])

  useEffect(() => {
    if (!container.current) return
    const chart = echarts.init(container.current)
    chart.setOption(option, { notMerge: true })
    const observer = new ResizeObserver(() => chart.resize())
    observer.observe(container.current)
    return () => {
      observer.disconnect()
      chart.dispose()
    }
  }, [option])

  return <div className="topology-chart" ref={container} style={{ width: '100%', height }} />
}

function buildOption(
  topology: PlanningTopology, scenario: TransferScenario | undefined, frame: SimulationPoint[], controlState?: Props['controlState'],
): EChartsCoreOption {
  const frameMap = new Map(frame.map((point) => [`${point.entityType}:${point.entityCode}`, point]))
  const feederX = new Map(topology.feeders.map((feeder, index) => [feeder.feederCode, 150 + index * 270]))
  const nodes: any[] = []
  const links: any[] = []

  topology.feeders.forEach((feeder) => {
    const point = frameMap.get(`FEEDER:${feeder.feederCode}`)
    nodes.push({
      id: feeder.feederCode,
      name: point
        ? `${feeder.feederCode}\n${(Number(point.loadRate) * 100).toFixed(1)}%`
        : `${feeder.feederCode}\n${feeder.feederName}`,
      x: feederX.get(feeder.feederCode),
      y: 125,
      symbol: 'roundRect',
      symbolSize: [150, 58],
      category: riskCategory(point?.riskStatus, scenario?.sourceFeederCode === feeder.feederCode),
      value: point,
      itemStyle: { color: nodeColor(point?.riskStatus, scenario?.sourceFeederCode === feeder.feederCode) },
    })
  })

  const grouped = new Map<string, typeof topology.transformers>()
  topology.transformers.forEach((transformer) => {
    const key = transformer.feederCode || 'UNKNOWN'
    grouped.set(key, [...(grouped.get(key) || []), transformer])
  })
  grouped.forEach((transformers, feederCode) => {
    transformers.forEach((transformer, index) => {
      const point = frameMap.get(`TRANSFORMER:${transformer.transformerCode}`)
      const belongsToOutage = scenario?.loadBlocks?.includes(transformer.transformerCode)
        || safeParseBlocks(scenario?.loadBlocksJson).includes(transformer.transformerCode)
      const isOutage = Boolean(belongsToOutage && (!controlState || controlState.sourceSwitchState === 'OPEN'))
      const isTarget = scenario?.targetTransformerCode === transformer.transformerCode && (!controlState || controlState.transferred)
      nodes.push({
        id: transformer.transformerCode,
        name: point
          ? `${transformer.transformerCode}\n${Number(point.afterLoadKw).toFixed(0)} kW · ${(Number(point.loadRate) * 100).toFixed(1)}%`
          : `${transformer.transformerCode}\n${transformer.transformerName}`,
        x: (feederX.get(feederCode) || 150) + (index - (transformers.length - 1) / 2) * 74,
        y: 330 + (index % 2) * 80,
        symbolSize: isTarget ? 76 : 64,
        category: isOutage ? '停运负荷' : isTarget ? '转供承载' : riskCategory(point?.riskStatus, false),
        value: point,
        itemStyle: {
          color: isOutage ? '#64748b' : isTarget ? nodeColor(point?.riskStatus, false, '#13a68a') : nodeColor(point?.riskStatus, false),
          borderColor: isTarget ? '#065f46' : '#fff',
          borderWidth: isTarget ? 4 : 2,
        },
      })
      links.push({
        source: feederCode,
        target: transformer.transformerCode,
        lineStyle: {
          color: isOutage ? '#94a3b8' : isTarget ? '#13a68a' : '#8aa4b8',
          width: isTarget ? 4 : 2,
          type: isOutage ? 'dashed' : 'solid',
          opacity: 0.9,
        },
      })
    })
  })

  topology.switches.filter((item) => item.switchType === 'TIE' && item.toFeederCode).forEach((item) => {
    const selected = scenario?.tieSwitchCode === item.switchCode
    const active = Boolean(selected && (!controlState || controlState.tieSwitchState === 'CLOSED'))
    links.push({
      source: item.fromFeederCode,
      target: item.toFeederCode,
      name: item.switchCode,
      symbol: ['none', active ? 'arrow' : 'none'],
      symbolSize: active ? 12 : 0,
      lineStyle: {
        color: active ? '#10b981' : selected ? '#f59f00' : '#cbd5e1',
        width: active ? 6 : selected ? 4 : 2,
        type: active ? 'solid' : 'dashed',
        curveness: 0.18,
        opacity: active ? 1 : 0.65,
      },
      label: { show: true, formatter: `${item.switchCode}${selected ? active ? ' · 已接通' : ' · 待接通' : ''}`, color: active ? '#047857' : selected ? '#b45309' : '#64748b', fontWeight: selected ? 700 : 400 },
    })
  })

  return {
    animationDurationUpdate: 650,
    animationEasingUpdate: 'cubicInOut' as const,
    tooltip: {
      formatter: (params: any) => {
        if (params.dataType === 'edge') return `${params.data.name || '供电连接'}<br/>${params.data.source} → ${params.data.target}`
        const point = params.data.value as SimulationPoint | undefined
        if (!point) return params.data.name.replace('\n', '<br/>')
        return `${params.data.id}<br/>仿真前：${Number(point.beforeLoadKw).toFixed(1)} kW<br/>转供量：${Number(point.transferLoadKw).toFixed(1)} kW<br/>仿真后：${Number(point.afterLoadKw).toFixed(1)} kW<br/>负载率：${(Number(point.loadRate) * 100).toFixed(1)}%`
      },
    },
    legend: [{
      bottom: 4,
      data: ['正常', '容量关注', '过载', '停运负荷', '转供承载'],
    }],
    series: [{
      type: 'graph' as const,
      layout: 'none' as const,
      roam: true,
      draggable: false,
      data: nodes,
      links,
      categories: [
        { name: '正常', itemStyle: { color: '#2f9e88' } },
        { name: '容量关注', itemStyle: { color: '#f59f00' } },
        { name: '过载', itemStyle: { color: '#e03131' } },
        { name: '停运负荷', itemStyle: { color: '#64748b' } },
        { name: '转供承载', itemStyle: { color: '#13a68a' } },
      ],
      label: { show: true, color: '#fff', fontSize: 11, fontWeight: 600, lineHeight: 15 },
      edgeLabel: { show: false },
      emphasis: { focus: 'adjacency', lineStyle: { width: 7 } },
      lineStyle: { color: '#9fb3c8', width: 2 },
    }],
  }
}

function riskCategory(status: SimulationPoint['riskStatus'] | undefined, outage: boolean) {
  if (outage) return '停运负荷'
  if (status === 'OVERLOAD') return '过载'
  if (status === 'WARNING') return '容量关注'
  return '正常'
}

function nodeColor(status: SimulationPoint['riskStatus'] | undefined, outage: boolean, normal = '#2f9e88') {
  if (outage) return '#64748b'
  if (status === 'OVERLOAD') return '#e03131'
  if (status === 'WARNING') return '#f59f00'
  return normal
}

function safeParseBlocks(value?: string) {
  try { return value ? JSON.parse(value) as string[] : [] } catch { return [] }
}
