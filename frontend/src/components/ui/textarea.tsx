import * as React from 'react'
import { cn } from '@/lib/utils'

/** Textarea（DS §10.3）：min-height 80px，竖向 resize，radius-md */
function Textarea({ className, ...props }: React.ComponentProps<'textarea'>) {
  return (
    <textarea
      className={cn(
        'flex min-h-20 w-full rounded-md border border-line bg-field px-3 py-2 text-base text-fg',
        'transition-colors duration-100 placeholder:text-faint resize-y',
        'hover:border-line-strong focus-visible:border-accent focus-visible:shadow-focus',
        'disabled:cursor-not-allowed disabled:bg-base disabled:text-faint',
        'aria-[invalid=true]:border-err-line',
        className,
      )}
      {...props}
    />
  )
}

export { Textarea }
