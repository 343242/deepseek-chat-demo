import { Outlet } from 'react-router'
import { Logo } from '@/components/shell/logo'
import { APP } from '@/lib/constants'

/** AuthShell（IA §3.3）—— 独立全屏，居中登录卡，极浅冷灰背景 */
export function AuthShell() {
  return (
    <div className="relative flex min-h-screen items-center justify-center bg-canvas px-4">
      {/* 极淡品牌光晕（DS AUTH-1：不喧宾夺主） */}
      <div
        className="pointer-events-none absolute inset-0 opacity-60"
        style={{ background: 'radial-gradient(60% 50% at 50% 0%, var(--brand-50) 0%, transparent 70%)' }}
      />
      <div className="relative z-10 w-full max-w-[400px]">
        <div className="mb-6 flex flex-col items-center gap-3">
          <Logo size={48} />
          <h1 className="text-2xl font-semibold text-fg">{APP.name}</h1>
        </div>
        <Outlet />
      </div>
    </div>
  )
}
