/**
 * 后端统一响应与分页契约
 * 来源：infrastructure/response/GlobalResponse.java · PagedResult.java
 */

/** 统一响应包装：code===0 成功，非 0 错误码 */
export interface GlobalResponse<T> {
  code: number
  message: string
  data: T
}

/** 通用分页结果（PagedResult<T>） */
export interface PagedResult<T> {
  content: T[]
  page: number
  size: number
  total: number
  totalPages: number
}

/** 分页请求参数（page 从 1 开始） */
export interface PageParams {
  page?: number
  size?: number
}

/**
 * 前端标准化 API 错误。
 * code 见 DESIGN-SYSTEM §4.4.12 错误码分段（0 成功 / 40100 未认证 / 40300 权限 / 42900 限流 / 50000 内部 …）
 */
export class ApiError extends Error {
  readonly code: number
  readonly status: number
  constructor(message: string, code: number, status = 0) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

/** 游标分页（消息历史，MessageCursorPage） */
export interface CursorPage<T> {
  items: T[]
  nextCursor: number | null
  hasMore: boolean
}
