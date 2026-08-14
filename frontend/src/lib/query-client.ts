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
        // 确定性业务错误不重试（重试必然再次失败），仅网络/5xx/瞬态错误重试
        if (error instanceof ApiError) {
          const c = error.code
          // 5 位 HTTP 风格码：401/403/404/429 等 4xx 客户端错误
          if (c === ERROR_CODE.UNAUTHORIZED || c === ERROR_CODE.FORBIDDEN) return false
          if (c >= 40000 && c < 50000) return false
          // 6 位业务码：A 类(100000–199999 客户端错误)、B 类(200000–299999 服务端业务错误)
          // C 类(300000+ 第三方)、D 类(400000+ 消息) 属瞬态，保留重试
          if (c >= 100000 && c < 300000) return false
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
