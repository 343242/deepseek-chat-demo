import * as React from 'react'
import { cn } from '@/lib/utils'

/** Skeleton（DS §10.18）：bg neutral-200，radius-sm，pulse 动画 */
function Skeleton({ className, ...props }: React.ComponentProps<'div'>) {
  return <div className={cn('animate-pulse rounded-sm bg-neutral-200 dark:bg-neutral-700', className)} {...props} />
}

export { Skeleton }
