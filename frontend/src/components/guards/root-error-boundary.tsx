import { Component, type ReactNode } from 'react'
import { AlertTriangle } from 'lucide-react'

interface Props {
  children: ReactNode
}
interface State {
  error: Error | null
}

/**
 * 根错误边界 —— 挂在 `<App>` 最外层（main.tsx）。
 *
 * 兜底两类会导致整屏白屏的异常（路由级代码分割后尤为必要）：
 * 1. 懒加载 chunk 加载失败 —— 网络抖动 / 新版本发布导致旧 chunk hash 失效，
 *    `React.lazy` 抛 `Failed to fetch dynamically imported module`。
 * 2. 任意子树渲染崩溃 —— 全工程此前无任何 ErrorBoundary，单点异常即白屏。
 *
 * 捕获后提供「重新加载」（reload 当前页，重新拉取最新 index.html 与 chunk hash）。
 * 刻意只依赖 lucide 单图标，避免自身引入易碎依赖链。
 */
export class RootErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  // componentDidUpdate 可选：路由切换后清除旧错误交还控制权给路由。
  // 当前最小实现不监听 location（边界在 BrowserRouter 内层，reload 已够用）。

  handleRetry = () => {
    window.location.reload()
  }

  render() {
    const { error } = this.state
    if (!error) return this.props.children

    return (
      <div className="flex h-screen flex-col items-center justify-center gap-3 bg-canvas px-6 text-center">
        <AlertTriangle className="size-8 text-error-600" />
        <p className="text-base font-medium text-fg">页面加载失败</p>
        <p className="max-w-sm text-sm text-muted">
          {error.message || '发生未知错误，请稍后重试'}
        </p>
        <button
          type="button"
          onClick={this.handleRetry}
          className="mt-2 rounded-md bg-primary-600 px-4 py-2 text-sm font-medium text-inv transition-colors hover:bg-primary-700"
        >
          重新加载
        </button>
      </div>
    )
  }
}
