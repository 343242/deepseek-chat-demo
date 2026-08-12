import { BrainCircuit } from 'lucide-react'
import { agentIntentLabel } from '@/lib/status-meta'
import type { AgentMetadata } from '@/types/chat'

/**
 * Agent 推理汇总视图（DS §11.4 当前可实现版：完整 6 事件回放待用户态端点 T3）。
 * 从 chat-page.tsx 抽离（FE-013：SRP），类型用 AgentMetadata（取代脆弱的 store 推断类型）。
 */
export function AgentSummary({ meta }: { meta: AgentMetadata }) {
  return (
    <div className="space-y-3 text-sm">
      <div className="rounded-md border border-line-subtle p-3">
        <div className="flex items-center gap-1.5 font-medium text-fg">
          <BrainCircuit className="size-4 text-primary-600" /> 意图识别
        </div>
        <p className="mt-1 text-muted">
          {agentIntentLabel(meta.intent)}
          {meta.confidence != null && <span> · 置信度 {meta.confidence.toFixed(2)}</span>}
        </p>
      </div>
      {meta.retrievalRounds != null && (
        <div className="rounded-md border border-line-subtle p-3">
          <div className="font-medium text-fg">检索轮数</div>
          <p className="mt-1 text-muted">{meta.retrievalRounds} 轮</p>
        </div>
      )}
      {meta.agentDegraded && (
        <div className="rounded-md bg-warning-50 p-3 text-warning-700">
          Agent 已降级为普通多轮对话{meta.degradedTo ? `（→ ${meta.degradedTo}）` : ''}
        </div>
      )}
      <p className="text-xs text-subtle">
        完整 6 事件推理时间线需后端用户态端点（T3，预留）。
      </p>
    </div>
  )
}
