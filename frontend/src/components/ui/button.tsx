import * as React from 'react'
import { Slot } from '@radix-ui/react-slot'
import { cva, type VariantProps } from 'class-variance-authority'
import { Loader2 } from 'lucide-react'
import { cn } from '@/lib/utils'

/**
 * Button（DS §10.1）
 * 变体 primary/secondary/outline/ghost/destructive/link；尺寸 sm/md/lg/icon
 * 圆角 radius-md(8px)；active scale(0.98) 触觉反馈；focus shadow-focus
 */
const buttonVariants = cva(
  'relative inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md font-medium transition-colors duration-100 select-none disabled:pointer-events-none disabled:opacity-60 active:scale-[0.98] [&_svg]:shrink-0 outline-none focus-visible:shadow-focus disabled:cursor-not-allowed',
  {
    variants: {
      variant: {
        primary: 'bg-primary-600 text-inv hover:bg-primary-700 shadow-xs',
        secondary: 'bg-surface text-fg border border-line hover:bg-hover',
        outline: 'border border-primary-600 text-primary-600 bg-transparent hover:bg-primary-50',
        ghost: 'bg-transparent text-muted hover:bg-hover hover:text-fg',
        destructive: 'bg-error-600 text-inv hover:bg-error-700 shadow-xs',
        link: 'bg-transparent text-link underline-offset-4 hover:underline',
      },
      size: {
        sm: 'h-7 px-3 text-base [&_svg]:size-3.5',
        md: 'h-9 px-4 text-base [&_svg]:size-4',
        lg: 'h-11 px-5 text-md [&_svg]:size-[18px]',
        icon: 'size-9 [&_svg]:size-4',
        'icon-sm': 'size-7 [&_svg]:size-3.5',
      },
    },
    defaultVariants: { variant: 'primary', size: 'md' },
  },
)

export interface ButtonProps
  extends React.ComponentProps<'button'>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean
  loading?: boolean
}

function Button({ className, variant, size, asChild, loading, children, disabled, ...props }: ButtonProps) {
  const Comp = asChild ? Slot : 'button'
  return (
    <Comp className={cn(buttonVariants({ variant, size }), className)} disabled={disabled || loading} {...props}>
      {loading && <Loader2 className="size-4 animate-spin" />}
      {children}
    </Comp>
  )
}

export { Button, buttonVariants }
