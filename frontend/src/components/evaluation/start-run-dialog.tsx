import { useState } from 'react'
import { Info, Play } from 'lucide-react'
import { toast } from 'sonner'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { useStartRun } from '@/api/evaluation'
import { EVALUATION } from '@/lib/constants'

/** 开关组配置（configOverride 布尔键；默认全开——与后端缺省一致，wireframe 09 §3.1） */
const RETRIEVAL_SWITCHES: { key: SwitchKey; label: string }[] = [
  { key: 'rerankEnabled', label: '重排' },
  { key: 'mmrEnabled', label: 'MMR 多样性' },
  { key: 'parentChildEnabled', label: '父子分块' },
  { key: 'queryRewriteEnabled', label: '查询改写' },
]

type SwitchKey = 'rerankEnabled' | 'mmrEnabled' | 'parentChildEnabled' | 'queryRewriteEnabled' | 'generationEnabled'

/**
 * 启动评测 Modal（线框 09 §3.1）——configOverride 全部可省略走后端默认；
 * 提交 → 202 → 自动切到该 Run 进度视图（EVAL-6：并发上限 2，帮助文本明示）。
 */
export function StartRunDialog({
  open,
  onOpenChange,
  datasetId,
  datasetName,
  onStarted,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  datasetId: number
  datasetName: string
  /** 启动成功（202）后回调 runId——页面切换到进度面板 */
  onStarted: (runId: number) => void
}) {
  const startRun = useStartRun()
  const [name, setName] = useState('')
  const [topKInput, setTopKInput] = useState('10')
  const [switches, setSwitches] = useState<Record<SwitchKey, boolean>>({
    rerankEnabled: true,
    mmrEnabled: true,
    parentChildEnabled: true,
    queryRewriteEnabled: true,
    generationEnabled: true,
  })
  const [testUserIdInput, setTestUserIdInput] = useState('')

  async function handleSubmit() {
    const topK = Number(topKInput.trim())
    if (!Number.isInteger(topK) || topK < EVALUATION.topKMin || topK > EVALUATION.topKMax) {
      toast.error(`Top K 需为 ${EVALUATION.topKMin}-${EVALUATION.topKMax} 的整数`)
      return
    }
    // 全部键都可省略；这里显式提交当前表单值（与默认一致时后端行为相同）
    const configOverride = {
      topK,
      ...switches,
      ...(testUserIdInput.trim() ? { testUserId: Number(testUserIdInput.trim()) } : {}),
    }
    try {
      const res = await startRun.mutateAsync({
        datasetId,
        name: name.trim() || undefined,
        configOverride,
      })
      toast.success('评测已提交，正在启动…')
      onOpenChange(false)
      onStarted(res.runId)
    } catch (e) {
      // 并发超限等后端错误（EVAL-6）
      toast.error((e as Error).message)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>启动评测 · {datasetName}</DialogTitle>
          <DialogDescription>可覆盖检索与生成配置；未调整的项走后端默认值。</DialogDescription>
        </DialogHeader>

        <div className="max-h-[60vh] space-y-4 overflow-y-auto pr-1">
          <div className="space-y-1.5">
            <Label htmlFor="eval-run-name">运行名称</Label>
            <Input
              id="eval-run-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="选填，缺省 run-<时间戳>"
            />
          </div>

          <div className="space-y-3">
            <p className="text-sm font-medium text-fg">检索配置</p>
            <div className="flex items-center justify-between">
              <Label htmlFor="eval-run-topk">Top K</Label>
              <Input
                id="eval-run-topk"
                value={topKInput}
                onChange={(e) => setTopKInput(e.target.value)}
                className="h-9 w-20 text-right"
                inputMode="numeric"
              />
            </div>
            {RETRIEVAL_SWITCHES.map((s) => (
              <div key={s.key} className="flex items-center justify-between">
                <Label htmlFor={`eval-run-${s.key}`}>{s.label}</Label>
                <Switch
                  id={`eval-run-${s.key}`}
                  checked={switches[s.key]}
                  onCheckedChange={(v) => setSwitches((prev) => ({ ...prev, [s.key]: v }))}
                />
              </div>
            ))}
          </div>

          <div className="space-y-3">
            <p className="text-sm font-medium text-fg">生成侧</p>
            <div className="flex items-center justify-between">
              <div>
                <Label htmlFor="eval-run-generation">生成答案并评分</Label>
                <p className="text-xs text-muted">关闭则无生成侧指标（结果表显示 '—'）</p>
              </div>
              <Switch
                id="eval-run-generation"
                checked={switches.generationEnabled}
                onCheckedChange={(v) => setSwitches((prev) => ({ ...prev, generationEnabled: v }))}
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="eval-run-user">测试用户 ID（选填）</Label>
            <Input
              id="eval-run-user"
              value={testUserIdInput}
              onChange={(e) => setTestUserIdInput(e.target.value)}
              placeholder="缺省用后端配置的测试用户"
              inputMode="numeric"
            />
          </div>

          <div className="flex items-start gap-2 rounded-md border border-line bg-muted/40 px-3 py-2 text-xs text-muted">
            <Info className="mt-0.5 size-3.5 shrink-0" />
            <span>
              最多同时运行 {EVALUATION.maxConcurrentRuns} 个评测，超出将启动失败；
              运行超过 30 分钟未结束会被系统标记失败。
            </span>
          </div>
        </div>

        <DialogFooter>
          <Button variant="secondary" onClick={() => onOpenChange(false)}>取消</Button>
          <Button onClick={() => void handleSubmit()} loading={startRun.isPending}>
            <Play className="size-4" /> 启动评测
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
