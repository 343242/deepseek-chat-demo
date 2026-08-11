import { Badge } from '@/components/ui/badge'
import type { StatusMeta } from '@/lib/status-meta'
import { cn } from '@/lib/utils'

/** 业务状态徽标（统一用 Badge + §4.4 映射 meta） */
export function StatusBadge({ meta, className }: { meta: StatusMeta; className?: string }) {
  const Icon = meta.icon
  return (
    <Badge variant={meta.variant} className={cn('gap-1', className)}>
      {Icon && <Icon className={cn(meta.spin && 'animate-spin')} />}
      {meta.label}
    </Badge>
  )
}
