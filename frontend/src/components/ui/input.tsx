import * as React from 'react'
import { cn } from '@/lib/utils'

/** Input（DS §10.2）：高 36px，padding 8px 12px，radius-md；hover/focus/error 态 */
function Input({ className, type, ...props }: React.ComponentProps<'input'>) {
  return (
    <input
      type={type}
      className={cn(
        'flex h-9 w-full rounded-md border border-line bg-field px-3 text-base text-fg',
        'transition-colors duration-100 placeholder:text-faint',
        'hover:border-line-strong focus-visible:border-accent focus-visible:shadow-focus',
        'disabled:cursor-not-allowed disabled:bg-base disabled:text-faint',
        'aria-[invalid=true]:border-err-line',
        className,
      )}
      {...props}
    />
  )
}

export { Input }
