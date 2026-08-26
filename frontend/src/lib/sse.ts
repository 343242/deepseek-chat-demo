import { apiFetch } from './api-fetch'
import type { ChatRequest, SseFrame, SseHandlers, CancelReason } from '@/types/chat'
import type { AgentMetadata, FallbackMeta } from '@/types/chat'
import type { Reference } from '@/types/document'
import type { EvalRunProgressEvent, EvalRunTerminalEvent } from '@/types/evaluation'

/**
 * SSE 流式解析（DS §11.3 关键约束）
 *
 * - 必须用 fetch + ReadableStream 手动解析（POST + @RequestBody，EventSource 不支持）
 * - 帧分隔：空行（\n\n）；字段以 `event:` / `data:` 开头
 * - 内容帧**无 event 名**（默认事件即内容）
 * - 不依赖"按 event 名分发"的高层封装，底层读行
 */

const SSE_URL = '/chat/stream'

/** 将一条原始 SSE 事件（event 名 + data 文本）映射为强类型帧 */
export function mapFrame(event: string | null, data: string): SseFrame | null {
  // 内容帧：无 event 名，data 直接是文本片段
  if (event === null || event === 'message' || event === '') {
    return { type: 'content', chunk: data }
  }
  switch (event) {
    case 'reasoning':
      return { type: 'reasoning', chunk: data }
    case 'reset': {
      // WS5 模型切换标记：from/to 解析失败兜底空串（与 fallback 帧同风格）
      const r = parseJson<{ from?: string; to?: string }>(data)
      return { type: 'reset', from: r?.from ?? '', to: r?.to ?? '' }
    }
    case 'usage': {
      const usage = parseJson<{ tokenUsage?: number | null; durationMs?: number | null }>(data)
      return { type: 'usage', tokenUsage: usage?.tokenUsage ?? null, durationMs: usage?.durationMs ?? null }
    }
    case 'references': {
      // 形状校验：后端契约漂移（返回对象而非数组）时兜底为空，而非带着错误类型下传（FE-018）
      const refs = parseJson<Reference[]>(data)
      return { type: 'references', references: Array.isArray(refs) ? refs : [] }
    }
    case 'agentMetadata':
      return { type: 'agentMetadata', metadata: parseJson<AgentMetadata>(data) ?? {} }
    case 'fallback':
      return {
        type: 'fallback',
        fallback: parseJson<FallbackMeta>(data) ?? { requestedModel: '', fallback: '' },
      }
    case 'canceled':
      return { type: 'canceled', reason: (parseJson<{ reason: CancelReason }>(data)?.reason ?? 'USER_ABORT') }
    case 'error':
      return {
        type: 'error',
        error: parseJson<{ error: string }>(data)?.error ?? 'UNKNOWN',
        message: parseJson<{ message: string }>(data)?.message ?? '生成失败',
      }
    default:
      // 未知事件名，忽略（前向兼容）
      return null
  }
}

/**
 * JSON.parse 包装：只保证「能解析」（失败返回 null），**不校验形状是否匹配 T**——
 * 形状校验由调用处按需补充（如 references 帧的 Array.isArray）。
 * 原 safeJson 名称会给读者「已校验」的错觉，故更名（FE-018）。
 */
export function parseJson<T>(text: string): T | null {
  if (!text) return null
  try {
    return JSON.parse(text) as T
  } catch {
    return null
  }
}

/** 识别用户主动中止（AbortController 触发的 AbortError） */
function isAbortError(err: unknown): boolean {
  return err instanceof Error && err.name === 'AbortError'
}

/** 提取异常文案；非 Error 抛出物（字符串等）走兜底文案（FE-016：catch 边界用守卫不用断言） */
function errorMessage(err: unknown): string {
  return err instanceof Error && err.message ? err.message : '网络异常'
}

function dispatch(frame: SseFrame, h: SseHandlers) {
  switch (frame.type) {
    case 'content':
      h.onContent?.(frame.chunk)
      break
    case 'reasoning':
      h.onReasoning?.(frame.chunk)
      break
    case 'reset':
      h.onReset?.(frame.from, frame.to)
      break
    case 'usage':
      h.onUsage?.(frame)
      break
    case 'references':
      h.onReferences?.(frame.references)
      break
    case 'agentMetadata':
      h.onAgentMetadata?.(frame.metadata)
      break
    case 'fallback':
      h.onFallback?.(frame.fallback)
      break
    case 'canceled':
      h.onCanceled?.(frame.reason)
      break
    case 'error':
      h.onError?.(frame)
      break
  }
}

/**
 * 启动流式聊天。返回 AbortController（调用 .abort() 兜底断流）。
 * 业务"停止"应调 POST /api/chat/stream/cancel（软取消，见 cancelChat）。
 */
export function streamChat(req: ChatRequest, handlers: SseHandlers): AbortController {
  const controller = new AbortController()

  void (async () => {
    try {
      const res = await fetch(`${SSE_URL.startsWith('http') ? '' : '/api'}${SSE_URL}`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify(req),
        signal: controller.signal,
      })

      if (!res.ok || !res.body) {
        const text = await res.text().catch(() => '')
        handlers.onError?.({
          type: 'error',
          error: 'HTTP_ERROR',
          message: text || `请求失败（${res.status}）`,
        })
        return
      }

      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let normalEnd = false

      try {
        while (true) {
          const { done, value } = await reader.read()
          if (done) {
            normalEnd = true
            break
          }
          buffer += decoder.decode(value, { stream: true })

          // 按空行切帧（\n\n），保留末尾不完整部分
          let sep: number
          while ((sep = buffer.indexOf('\n\n')) !== -1) {
            const raw = buffer.slice(0, sep)
            buffer = buffer.slice(sep + 2)
            const frame = parseEventBlock(raw)
            if (frame) dispatch(frame, handlers)
          }
        }
      } catch (err) {
        if (isAbortError(err)) return
        handlers.onError?.({
          type: 'error',
          error: 'NETWORK',
          message: errorMessage(err),
        })
        return
      }

      if (normalEnd) handlers.onComplete?.()
    } catch (err) {
      if (isAbortError(err)) return
      handlers.onError?.({
        type: 'error',
        error: 'NETWORK',
        message: errorMessage(err),
      })
    }
  })()

  return controller
}

/** 解析一个 SSE 事件块（多行文本）为帧 */
export function parseEventBlock(block: string): SseFrame | null {
  const lines = block.split('\n')
  let event: string | null = null
  const dataLines: string[] = []

  for (const line of lines) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
    // 忽略 id:/retry:/注释行
  }
  if (dataLines.length === 0) return null
  return mapFrame(event, dataLines.join('\n'))
}

/** 软取消：POST /api/chat/stream/cancel（幂等，已结束返回 cancelled:false 不报错） */
export function cancelChat(conversationId: string, reason: CancelReason = 'USER_ABORT') {
  return apiFetch<{ cancelled: boolean }>('/chat/stream/cancel', {
    method: 'POST',
    json: { conversationId, reason },
  }).catch(() => ({ cancelled: false }))
}

/* ============ 评估运行进度订阅（DS §11.17 · 线框 09 §3.3） ============ */

export interface EvalRunEventHandlers {
  onProgress?: (event: EvalRunProgressEvent) => void
  /** 终态：流上 done 帧或已结束 run 的终态回放 */
  onDone?: (event: EvalRunTerminalEvent) => void
  /** 后端 event:error 帧（run 失败或进度流异常收尾） */
  onError?: (event: EvalRunTerminalEvent) => void
  /** 连接层断开（断流/404）。EventSource 会自动重连（DS §11.17）；组件以此切换轮询兜底 */
  onConnectionError?: () => void
}

/**
 * 订阅评测运行 SSE（GET /api/evaluation/runs/{runId}/events，EventSource 原生自动重连）。
 *
 * 与 chat 的 POST 流不同（DS §11.3 vs §11.17）：GET 端点可用 EventSource；迟到订阅后端 replay
 * 最近 20 条 progress；已结束（completed/failed）的 run 后端直接回放单帧终态后关流。
 * 返回退订函数——"后台运行"关闭面板只退订不中断评测（SSE 只是观察窗，不承载执行）。
 */
export function subscribeEvalRunEvents(runId: number, handlers: EvalRunEventHandlers): () => void {
  const source = new EventSource(`/api/evaluation/runs/${runId}/events`, { withCredentials: true })

  let closed = false
  /** 断开只上报一次（重连成功收到任意帧后复位），避免自动重连期重复告警 */
  let disconnected = false
  const close = () => {
    if (closed) return
    closed = true
    source.close()
  }
  const markConnected = () => {
    disconnected = false
  }

  source.addEventListener('progress', (e) => {
    markConnected()
    const data = parseJson<EvalRunProgressEvent>((e as MessageEvent<string>).data)
    if (data) handlers.onProgress?.(data)
  })

  source.addEventListener('done', (e) => {
    markConnected()
    const data = parseJson<EvalRunTerminalEvent>((e as MessageEvent<string>).data) ?? {}
    handlers.onDone?.(data)
    close()
  })

  // EventSource 的 'error' 监听同时收到两类事件：后端 event:error 帧（MessageEvent，有 data）
  // 与连接层错误（无 data）。前者是业务终态需 close，后者依赖自动重连。
  source.addEventListener('error', (e) => {
    const me = e as MessageEvent<string>
    if (typeof me.data === 'string') {
      markConnected()
      const data = parseJson<EvalRunTerminalEvent>(me.data) ?? {}
      handlers.onError?.(data)
      close()
      return
    }
    if (closed || disconnected) return
    disconnected = true
    handlers.onConnectionError?.()
  })

  return close
}
