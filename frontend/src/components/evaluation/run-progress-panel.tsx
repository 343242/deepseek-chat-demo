import { useEffect, useMemo, useRef, useState } from 'react'
import { CheckCircle2, RefreshCw, XCircle } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Progress } from '@/components/ui/progress'
import { subscribeEvalRunEvents } from '@/lib/sse'
import { formatDuration } from '@/lib/format'
import { cn } from '@/lib/utils'
import type { EvalRunProgressEvent } from '@/types/evaluation'

/** 实时日志条目（内存中最多保留最近 50 条，DS §11.17） */
interface LogEntry {
  itemId: number
  ok: boolean
  latencyMs: number
  error?: string | null
}

const MAX_LOGS = 50

/**
 * EvalRunProgress（DS §11.17 · 线框 09 §3.3）——SSE 实时进度面板。
 *
 * - 订阅 GET /runs/{runId}/events：progress 帧驱动进度条与日志；done/error 帧收尾
 * - "后台运行"只退订不中断评测（SSE 是观察窗，不承载执行）
 * - 断线：EventSource 自动重连；连接层错误提示 + 手动重订阅兜底（线框 §5）
 * - 迟到订阅：后端 replay 最近 20 条 progress，面板自然恢复（design.md §5 之外行为）
 */
export function RunProgressPanel({
  runId,
  runName,
  onBackground,
  onFinished,
}: {
  runId: number
  runName: string
  /** 后台运行（关闭面板，不中断评测） */
  onBackground: () => void
  /** 终态收尾（done / error 帧）：刷新列表并跳转结果 */
  onFinished: (failed: boolean) => void
}) {
  const [latest, setLatest] = useState<EvalRunProgressEvent | null>(null)
  const [logs, setLogs] = useState<LogEntry[]>([])
  const [finished, setFinished] = useState<'done' | 'error' | null>(null)
  const [disconnected, setDisconnected] = useState(false)
  /** 重订阅钥匙（手动刷新递增） */
  const [subKey, setSubKey] = useState(0)
  const finishedRef = useRef(false)

  useEffect(() => {
    const unsubscribe = subscribeEvalRunEvents(runId, {
      onProgress: (event) => {
        setLatest(event)
        setLogs((prev) => {
          const next = [
            { itemId: event.itemId, ok: event.status === 'success', latencyMs: event.elapsedMs, error: event.error },
            ...prev,
          ]
          return next.slice(0, MAX_LOGS)
        })
      },
      onDone: () => {
        if (finishedRef.current) return
        finishedRef.current = true
        setFinished('done')
        toast.success('评测运行结束')
        onFinished(false)
      },
      onError: (event) => {
        if (finishedRef.current) return
        finishedRef.current = true
        setFinished('error')
        toast.error(event.message ?? '评测进度流异常')
        onFinished(true)
      },
      onConnectionError: () => setDisconnected(true),
    })
    return unsubscribe
    // eslint-disable-next-line react-hooks/exhaustive-deps -- onFinished 由页面传入的行内闭包，依赖 subKey/runId 重订阅
  }, [runId, subKey])

  /** 手动重订阅：重置终态/断连标记后递增 subKey 触发 effect 重跑（状态清理收口在事件处理器） */
  function handleRefresh() {
    finishedRef.current = false
    setFinished(null)
    setDisconnected(false)
    setSubKey((k) => k + 1)
  }

  const processed = latest?.processed ?? 0
  const total = latest?.total ?? 0
  const percent = total > 0 ? Math.round((processed / total) * 100) : 0
  const recentLogs = useMemo(() => logs, [logs])

  return (
    <div className="rounded-lg border border-line bg-surface p-4">
      <div className="mb-3 flex items-center justify-between gap-2">
        <h3 className="text-sm font-medium text-fg">评测进度 · {runName}</h3>
        <div className="flex items-center gap-2">
          <Button size="sm" variant="secondary" onClick={handleRefresh} disabled={finished === 'done'}>
            <RefreshCw className="size-4" /> 刷新
          </Button>
          <Button size="sm" variant="secondary" onClick={onBackground} disabled={finished != null}>
            后台运行
          </Button>
        </div>
      </div>

      {disconnected && finished == null && (
        <p className="mb-2 rounded-md border border-warning-600/40 bg-warning-600/10 px-3 py-1.5 text-xs text-warning-700">
          进度流中断，正在自动重连；可点击"刷新"手动重订阅，或后台运行等待列表轮询结果。
        </p>
      )}

      <div className="mb-2 flex items-center justify-between text-sm tabular-nums">
        <span className="text-fg">{processed} / {total}</span>
        <span className="text-muted">{percent}%</span>
      </div>
      <Progress value={percent} />

      <div className="mt-3 flex flex-wrap items-center gap-4 text-sm">
        <span className="inline-flex items-center gap-1 text-success-700">
          <CheckCircle2 className="size-4" /> 成功 {latest?.successCount ?? 0}
        </span>
        <span className="inline-flex items-center gap-1 text-error-700">
          <XCircle className="size-4" /> 失败 {latest?.failCount ?? 0}
        </span>
        <span className="text-muted">已运行 {formatDuration(latest?.elapsedMs ?? 0) || '—'}</span>
        {finished === 'done' && <span className="text-success-700">已完成</span>}
        {finished === 'error' && <span className="text-error-700">已结束（失败）</span>}
      </div>

      {recentLogs.length > 0 && (
        <div className="mt-3 max-h-48 overflow-y-auto rounded-md border border-line bg-base p-2">
          <p className="mb-1 px-1 text-xs text-muted">实时日志（最近 {recentLogs.length} 条）</p>
          <ul className="space-y-0.5 font-mono text-xs">
            {recentLogs.map((log, i) => (
              <li key={`${log.itemId}-${i}`} className="flex items-center gap-2 px-1">
                <span className={cn('shrink-0', log.ok ? 'text-success-700' : 'text-error-700')}>
                  #{log.itemId} {log.ok ? '✓' : '✗'}
                </span>
                <span className="truncate text-muted">
                  {log.ok ? `${formatDuration(log.latencyMs)}` : `error: ${log.error ?? '未知'}`}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
