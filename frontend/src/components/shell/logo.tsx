import { APP } from '@/lib/constants'
import { cn } from '@/lib/utils'

/** Logo 方块（DS §1.3 / §15.1 brand-logo-bg）—— 文字占位，圆角 radius-lg */
export function Logo({ size = 32, className }: { size?: number; className?: string }) {
  return (
    <div className="flex items-center gap-2">
      <div
        className={cn('flex items-center justify-center rounded-lg bg-primary-600 font-bold text-inv', className)}
        style={{ width: size, height: size, fontSize: size * 0.4 }}
      >
        {APP.logo}
      </div>
    </div>
  )
}

/** 仅方块（用于紧凑场景） */
export function LogoMark({ size = 32 }: { size?: number }) {
  return (
    <div
      className="flex items-center justify-center rounded-lg bg-primary-600 font-bold text-inv"
      style={{ width: size, height: size, fontSize: size * 0.4 }}
    >
      {APP.logo}
    </div>
  )
}
