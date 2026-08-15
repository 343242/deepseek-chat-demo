import { useMemo, useState } from 'react'
import { ChevronsUpDown, Search, Check, Bot } from 'lucide-react'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Input } from '@/components/ui/input'
import { useModels } from '@/api/models'
import { useRefreshModels } from '@/api/models'
import { usePermission } from '@/hooks/use-permission'
import { RefreshCw } from 'lucide-react'
import { toast } from 'sonner'
import { PERMISSION } from '@/lib/constants'
import { cn, getOrCreate } from '@/lib/utils'
import type { ModelVO } from '@/types/chat'

/** ModelSelector（DS §11.2）—— 按 provider 分组，含搜索；仅暴露 candidate id（不含 /） */
export function ModelSelector({ value, onChange }: { value: string; onChange: (id: string) => void }) {
  const { data: models, isLoading } = useModels()
  const canRefresh = usePermission().has(PERMISSION.MODEL_CONFIG)
  const refresh = useRefreshModels()
  const [open, setOpen] = useState(false)
  const [kw, setKw] = useState('')

  const available = useMemo(() => (models ?? []).filter((m) => m.available), [models])

  const grouped = useMemo(() => {
    const map = new Map<string, ModelVO[]>()
    for (const m of available) {
      const p = m.provider || '其他'
      getOrCreate(map, p, () => []).push(m)
    }
    const kwLower = kw.trim().toLowerCase()
    return Array.from(map.entries())
      .map(([provider, items]) => ({
        provider,
        items: kwLower ? items.filter((m) => m.id.toLowerCase().includes(kwLower) || m.model.toLowerCase().includes(kwLower)) : items,
      }))
      .filter((g) => g.items.length)
  }, [available, kw])

  const current = available.find((m) => m.id === value)

  async function handleRefresh() {
    try {
      await refresh.mutateAsync()
      toast.success('模型列表已刷新')
    } catch {
      toast.error('刷新失败，请稍后重试')
    }
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          className="flex h-9 items-center gap-1.5 rounded-md border border-line bg-field px-3 text-sm text-fg transition-colors hover:border-line-strong focus-visible:shadow-focus"
        >
          <Bot className="size-4 text-primary-600" />
          <span className="max-w-[160px] truncate">{current?.id ?? value ?? '选择模型'}</span>
          <ChevronsUpDown className="size-3.5 text-faint" />
        </button>
      </PopoverTrigger>
      <PopoverContent className="w-72 p-0" align="start">
        <div className="border-b border-line-subtle p-2">
          <div className="relative">
            <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-faint" />
            <Input value={kw} onChange={(e) => setKw(e.target.value)} placeholder="搜索模型" className="h-8 pl-8 text-sm" />
          </div>
        </div>
        <div className="max-h-64 overflow-y-auto p-1">
          {isLoading ? (
            <div className="py-6 text-center text-sm text-subtle">加载中…</div>
          ) : grouped.length === 0 ? (
            <div className="py-6 text-center text-sm text-subtle">无匹配项</div>
          ) : (
            grouped.map((g) => (
              <div key={g.provider} className="mb-1">
                <div className="px-2 py-1 text-xs font-medium uppercase text-subtle">{g.provider}</div>
                {g.items.map((m) => (
                  <button
                    key={m.id}
                    type="button"
                    onClick={() => {
                      onChange(m.id)
                      setOpen(false)
                      setKw('')
                    }}
                    className={cn(
                      'flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm transition-colors hover:bg-hover',
                      m.id === value && 'bg-selected',
                    )}
                  >
                    <Check className={cn('size-4 shrink-0', m.id === value ? 'text-primary-600' : 'opacity-0')} />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate font-medium text-fg">{m.id}</span>
                      <span className="block truncate text-xs text-subtle">{m.model}</span>
                    </span>
                  </button>
                ))}
              </div>
            ))
          )}
        </div>
        {canRefresh && (
          <div className="border-t border-line-subtle p-1">
            <button
              type="button"
              onClick={() => void handleRefresh()}
              disabled={refresh.isPending}
              className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-sm text-muted transition-colors hover:bg-hover hover:text-fg"
            >
              <RefreshCw className={cn('size-4', refresh.isPending && 'animate-spin')} /> 刷新模型列表
            </button>
          </div>
        )}
      </PopoverContent>
    </Popover>
  )
}
