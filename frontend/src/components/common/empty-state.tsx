import * as React from 'react'
import { cn } from '@/lib/utils'

/** Empty 空状态（DS §10.20）—— 居中，图标 + 标题 + 描述 + 可选行动按钮 */
export interface EmptyStateProps {
  icon?: React.ReactNode
  title: string
  description?: string
  action?: React.ReactNode
  className?: string
}

export function EmptyState({ icon, title, description, action, className }: EmptyStateProps) {
  return (
    <div className={cn('flex flex-col items-center justify-center px-6 py-12 text-center', className)}>
      {icon && (
        <div className="mb-4 flex size-12 items-center justify-center rounded-full bg-base text-faint [&_svg]:size-6">
          {icon}
        </div>
      )}
      <h3 className="text-lg font-semibold text-fg">{title}</h3>
      {description && <p className="mt-1 max-w-xs text-sm text-muted">{description}</p>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  )
}
