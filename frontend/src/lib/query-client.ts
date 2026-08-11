import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/types/api'
import { ERROR_CODE } from './constants'

/** TanStack Query 全局配置（DS §15.4：TanStack 负责编排/缓存/失效） */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 数据相对静态，减少抖动
      staleTime: 30_000,
      gcTime: 5 * 60_000,
      retry: (failureCount, error) => {
        // 业务错误（401/403/400 等）不重试，仅网络/5xx 重试
        if (error instanceof ApiError) {
          const c = error.code
          if (c === ERROR_CODE.UNAUTHORIZED || c === ERROR_CODE.FORBIDDEN) return false
          if (c >= 40000 && c < 50000) return false
        }
        return failureCount < 2
      },
      refetchOnWindowFocus: false,
    },
    mutations: {
      retry: false,
    },
  },
})
