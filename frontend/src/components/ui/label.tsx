import * as React from 'react'
import * as LabelPrimitive from '@radix-ui/react-label'
import { cn } from '@/lib/utils'

/** Label（DS §10.22）：font-medium，必填 *（由调用方在 children 内渲染） */
function Label({ className, ...props }: React.ComponentProps<typeof LabelPrimitive.Root>) {
  return (
    <LabelPrimitive.Root
      className={cn(
        'text-md font-medium text-fg leading-none select-none',
        'peer-disabled:cursor-not-allowed peer-disabled:text-faint',
        className,
      )}
      {...props}
    />
  )
}

export { Label }
