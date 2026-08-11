import { apiFetch } from './api-fetch'
import type { ChatRequest, SseFrame, SseHandlers, CancelReason } from '@/types/chat'
import type { AgentMetadata, FallbackMeta } from '@/types/chat'
import type { Reference } from '@/types/document'

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
function mapFrame(event: string | null, data: string): SseFrame | null {
  // 内容帧：无 event 名，data 直接是文本片段
  if (event === null || event === 'message' || event === '') {
    return { type: 'content', chunk: data }
  }
  switch (event) {
    case 'reasoning':
      return { type: 'reasoning', chunk: data }
    case 'references':
      return { type: 'references', references: safeJson<Reference[]>(data) ?? [] }
    case 'agentMetadata':
      return { type: 'agentMetadata', metadata: safeJson<AgentMetadata>(data) ?? {} }
    case 'fallback':
      return {
        type: 'fallback',
        fallback: safeJson<FallbackMeta>(data) ?? { requestedModel: '', fallback: '' },
      }
    case 'canceled':
      return { type: 'canceled', reason: (safeJson<{ reason: CancelReason }>(data)?.reason ?? 'USER_ABORT') }
    case 'error':
      return {
        type: 'error',
        error: safeJson<{ error: string }>(data)?.error ?? 'UNKNOWN',
        message: safeJson<{ message: string }>(data)?.message ?? '生成失败',
      }
    default:
      // 未知事件名，忽略（前向兼容）
      return null
  }
}

function safeJson<T>(text: string): T | null {
  if (!text) return null
  try {
    return JSON.parse(text) as T
  } catch {
    return null
  }
}

function dispatch(frame: SseFrame, h: SseHandlers) {
  switch (frame.type) {
    case 'content':
      h.onContent?.(frame.chunk)
      break
    case 'reasoning':
      h.onReasoning?.(frame.chunk)
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
        if ((err as Error).name === 'AbortError') return
        handlers.onError?.({
          type: 'error',
          error: 'NETWORK',
          message: (err as Error).message || '网络异常',
        })
        return
      }

      if (normalEnd) handlers.onComplete?.()
    } catch (err) {
      if ((err as Error).name === 'AbortError') return
      handlers.onError?.({
        type: 'error',
        error: 'NETWORK',
        message: (err as Error).message || '网络异常',
      })
    }
  })()

  return controller
}

/** 解析一个 SSE 事件块（多行文本）为帧 */
function parseEventBlock(block: string): SseFrame | null {
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
