import type { SseFrame } from '@/types/chat'
import type { RenderMessage } from '@/types/chat'

/**
 * 流式消息 reducer（FE-006：从 chat-store.send() 抽出的纯函数）。
 *
 * 把一帧 SSE 应用到「当前消息数组」，返回新数组（不可变）。
 * 仅处理**消息数组**的变更；streaming/error 等顶层态与 query 失效由编排层（send）负责。
 *
 * 抽出的动机（SRP/KISS/可测）：原 send() 是 ~100 行的上帝函数，7 类帧各自内联 mutation、不可单测。
 * 这里集中后可对每类帧独立写单测。
 */

/** 对目标 assistant 消息做不可变 patch；其余消息原样保留（引用不变） */
function patch(
  messages: RenderMessage[],
  assistantId: number,
  fn: (m: RenderMessage) => RenderMessage,
): RenderMessage[] {
  return messages.map((m) => (m.id === assistantId ? fn(m) : m))
}

/**
 * 将一帧 SSE 应用到消息数组。
 * - content / reasoning：追加文本
 * - reset：清空已累积 content/reasoning 并记录模型切换（WS5，新模型从头生成）
 * - references / agentMetadata / fallback：替换字段
 * - canceled：消息转 FINISHED
 * - error：消息转 ERROR（错误文本属顶层态，由 send 写入 store.error，不在此处理）
 */
export function applyFrame(
  messages: RenderMessage[],
  frame: SseFrame,
  assistantId: number,
): RenderMessage[] {
  switch (frame.type) {
    case 'content':
      return patch(messages, assistantId, (m) => ({ ...m, content: m.content + frame.chunk }))
    case 'reasoning':
      return patch(messages, assistantId, (m) => ({
        ...m,
        reasoning: (m.reasoning ?? '') + frame.chunk,
      }))
    case 'reset':
      // 模型切换（WS5）：旧模型的半截回答/思考已失效，清空后由新模型内容重新累积
      return patch(messages, assistantId, (m) => ({
        ...m,
        content: '',
        reasoning: '',
        modelReset: { from: frame.from, to: frame.to },
      }))
    case 'references':
      return patch(messages, assistantId, (m) => ({ ...m, references: frame.references }))
    case 'usage':
      return patch(messages, assistantId, (m) => ({
        ...m,
        tokenUsage: frame.tokenUsage ?? null,
        durationMs: frame.durationMs ?? null,
      }))
    case 'agentMetadata':
      return patch(messages, assistantId, (m) => ({ ...m, agentMetadata: frame.metadata }))
    case 'fallback':
      return patch(messages, assistantId, (m) => ({ ...m, fallback: frame.fallback }))
    case 'canceled':
      return patch(messages, assistantId, (m) => ({ ...m, status: 'FINISHED' }))
    case 'error':
      return patch(messages, assistantId, (m) => ({ ...m, status: 'ERROR' }))
  }
}

/**
 * 收尾：流结束时把仍在 IN_PROGRESS 的 assistant 消息置为 FINISHED。
 * （正常完成 / 软取消后服务端关闭流都会走到此）
 * 同时无条件清除 pending——消息已定型，tokenUsage/durationMs 等 meta 不再受"临时消息"条件遮挡
 * （含 canceled 分支转入的 FINISHED 消息）。
 */
export function finalizeInProgress(messages: RenderMessage[], assistantId: number): RenderMessage[] {
  return patch(messages, assistantId, (m) => ({
    ...m,
    status: m.status === 'IN_PROGRESS' ? 'FINISHED' : m.status,
    pending: false,
  }))
}
