import * as React from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'

/** Badge / Tag（DS §10.8）—— 胶囊变体默认；业务状态徽标统一用此组件 + §4.4 映射 */
const badgeVariants = cva(
  'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap [&_svg]:size-3',
  {
    variants: {
      variant: {
        neutral: 'bg-base text-muted',
        brand: 'bg-primary-50 text-primary-700',
        success: 'bg-success-50 text-success-700',
        warning: 'bg-warning-50 text-warning-700',
        error: 'bg-error-50 text-error-700',
        outline: 'border border-line text-muted',
      },
      /** 方角变体（紧凑信息块） */
      square: { true: 'rounded-sm', false: '' },
    },
    defaultVariants: { variant: 'neutral', square: false },
  },
)

export interface BadgeProps
  extends React.ComponentProps<'span'>,
    VariantProps<typeof badgeVariants> {}

function Badge({ className, variant, square, ...props }: BadgeProps) {
  return <span className={cn(badgeVariants({ variant, square }), className)} {...props} />
}

export { Badge, badgeVariants }
