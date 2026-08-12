import { Loader2 } from 'lucide-react'

/**
 * 路由级懒加载兜底（`React.lazy` + `<Suspense fallback>`）。
 * 与 `RequireAuth` 的加载态保持一致的视觉（居中 spinner on bg-canvas）。
 */
export function RouteSkeleton() {
  return (
    <div className="flex h-screen items-center justify-center bg-canvas">
      <Loader2 className="size-6 animate-spin text-primary-600" />
    </div>
  )
}
