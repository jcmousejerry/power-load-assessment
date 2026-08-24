import { Typography } from 'antd'
import type { ReactNode } from 'react'

export default function PageHeader({
  title,
  description,
  extra,
}: {
  title: string
  description: string
  extra?: ReactNode
}) {
  return (
    <div className="page-header">
      <div>
        <Typography.Title level={2}>{title}</Typography.Title>
        <Typography.Paragraph type="secondary">{description}</Typography.Paragraph>
      </div>
      {extra}
    </div>
  )
}
