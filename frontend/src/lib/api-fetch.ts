import { ApiError, type GlobalResponse } from '@/types/api'
import { ERROR_CODE } from './constants'

/**
 * apiFetch —— 全局 HTTP 传输薄封装（非 axios，DS §15.4）
 *
 * 三件定制集中于此：
 * 1. Cookie 凭证：所有请求 `credentials: 'include'`（Token 在 HttpOnly Cookie）
 * 2. GlobalResponse 双轨制：HTTP 200 但业务 `code !== 0` 视为错误；`code === 0` 解包返回 data
 * 3. 401 → refresh 单例锁 → 重放原请求（IA-6 并发控制）；refresh 失败派发 unauthorized 事件
 */

const BASE = '/api'

export interface ApiFetchOptions extends Omit<RequestInit, 'body'> {
  /** JSON 请求体（自动序列化，便于 401 重放时重新构造） */
  json?: unknown
  /** 原始请求体（FormData / Blob / 八进制流；与 json 二选一） */
  body?: BodyInit | null
  /** query 参数 */
  params?: Record<string, string | number | boolean | undefined | null>
  /** 内部用：防止 refresh 重放死循环 */
  _retried?: boolean
}

function buildUrl(path: string, params?: ApiFetchOptions['params']): string {
  const url = path.startsWith('http') ? path : `${BASE}${path}`
  if (!params) return url
  const sp = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') sp.append(k, String(v))
  }
  const qs = sp.toString()
  return qs ? `${url}?${qs}` : url
}

/** 运行时校验统一响应信封形状（FE-018：边界 JSON 不再裸断言） */
function isGlobalResponse(body: unknown): body is GlobalResponse<unknown> {
  if (typeof body !== 'object' || body === null) return false
  return (
    'code' in body &&
    typeof body.code === 'number' &&
    'message' in body &&
    typeof body.message === 'string' &&
    'data' in body
  )
}

/** refresh 单例锁（IA-6：并发 401 共享同一 Promise，只发一次 refresh） */
let refreshPromise: Promise<boolean> | null = null

async function doRefresh(): Promise<boolean> {
  if (refreshPromise) return refreshPromise
  refreshPromise = (async () => {
    try {
      const res = await fetch(`${BASE}/auth/refresh`, {
        method: 'POST',
        credentials: 'include',
        headers: { Accept: 'application/json' },
      })
      if (!res.ok) return false
      const body: unknown = await res.json()
      return isGlobalResponse(body) && body.code === ERROR_CODE.SUCCESS
    } catch {
      return false
    } finally {
      refreshPromise = null
    }
  })()
  return refreshPromise
}

/** 派发未认证事件（main.tsx 监听 → 跳登录，保留 redirect） */
function emitUnauthorized() {
  window.dispatchEvent(new CustomEvent('srag:unauthorized'))
}

/**
 * 发起请求并解包 GlobalResponse。
 * @returns 成功时 `data` 字段（raw 模式返回原始响应体）
 */
export async function apiFetch<T>(path: string, opts: ApiFetchOptions = {}): Promise<T> {
  const { json, body: rawBody, params, _retried, headers, ...rest } = opts

  const finalHeaders = new Headers(headers)
  let body: BodyInit | null | undefined
  if (json !== undefined) {
    finalHeaders.set('Content-Type', 'application/json')
    body = JSON.stringify(json)
  } else if (rawBody !== undefined) {
    body = rawBody
  }

  const url = buildUrl(path, params)
  // TEMP-DEBUG(联调诊断): info 级请求链路追踪，定位后删除
  console.info(
    `[trace] REQ ${opts.method ?? 'GET'} ${url} | origin=${typeof window !== 'undefined' ? window.location.origin : '(ssr)'} | contentType=${finalHeaders.get('Content-Type') ?? '-'} | hasBody=${body != null} | credentials=include`,
  )
  const res = await fetch(url, {
    ...rest,
    headers: finalHeaders,
    body,
    credentials: 'include',
  })
  // TEMP-DEBUG(联调诊断): 响应状态 + CORS 允许源（403 且无 ACAO 头 = CorsFilter 拒绝）
  console.info(
    `[trace] RES ${res.status} ${url} | acao=${res.headers.get('access-control-allow-origin') ?? '-'} | contentType=${res.headers.get('content-type') ?? '-'}`,
  )

  // 401 → 尝试 refresh → 重放（仅一次）
  if (res.status === 401 && !_retried) {
    const ok = await doRefresh()
    if (ok) {
      return apiFetch<T>(path, { ...opts, _retried: true })
    }
    emitUnauthorized()
    throw new ApiError('登录已过期，请重新登录', ERROR_CODE.UNAUTHORIZED, 401)
  }

  // 非 JSON 响应（如 blob/文本）直接返回。剩余唯一信任点：文本体无法运行时校验 T 的形状（FE-018）
  const contentType = res.headers.get('content-type') ?? ''
  if (!contentType.includes('application/json')) {
    if (!res.ok) throw new ApiError(`请求失败（${res.status}）`, ERROR_CODE.INTERNAL, res.status)
    return (await res.text()) as unknown as T
  }

  const envelope: unknown = await res.json()
  if (!isGlobalResponse(envelope)) {
    throw new ApiError('响应格式异常，请稍后重试', ERROR_CODE.INTERNAL, res.status)
  }

  // 业务错误
  if (envelope.code !== ERROR_CODE.SUCCESS) {
    // 业务层 40100 也触发未认证（保险，通常 HTTP 已是 401）
    if (envelope.code === ERROR_CODE.UNAUTHORIZED && !_retried) {
      emitUnauthorized()
    }
    throw new ApiError(envelope.message || '请求失败', envelope.code, res.status)
  }

  // 信封形状已运行时校验；data 内容由调用方声明的 T 负责（泛型信任点）
  return envelope.data as T
}

/** GET 便捷方法 */
export const api = {
  get: <T>(path: string, opts?: Omit<ApiFetchOptions, 'json' | 'method'>) =>
    apiFetch<T>(path, { ...opts, method: 'GET' }),
  post: <T>(path: string, json?: unknown, opts?: Omit<ApiFetchOptions, 'json' | 'method'>) =>
    apiFetch<T>(path, { ...opts, method: 'POST', json }),
  put: <T>(path: string, json?: unknown, opts?: Omit<ApiFetchOptions, 'json' | 'method'>) =>
    apiFetch<T>(path, { ...opts, method: 'PUT', json }),
  del: <T>(path: string, opts?: Omit<ApiFetchOptions, 'json' | 'method'>) =>
    apiFetch<T>(path, { ...opts, method: 'DELETE' }),
}
