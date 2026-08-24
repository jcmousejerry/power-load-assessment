import * as echarts from 'echarts/core'
import type { EChartsCoreOption } from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useEffect, useRef } from 'react'

echarts.use([
  BarChart,
  LineChart,
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  CanvasRenderer,
])

export default function Chart({ option, height = 380 }: { option: EChartsCoreOption; height?: number }) {
  const container = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!container.current) return
    const chart = echarts.init(container.current)
    chart.setOption(option)
    const observer = new ResizeObserver(() => chart.resize())
    observer.observe(container.current)
    return () => {
      observer.disconnect()
      chart.dispose()
    }
  }, [option])

  return <div ref={container} style={{ width: '100%', height }} />
}
