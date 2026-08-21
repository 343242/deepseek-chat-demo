import { useState } from 'react'
import { Sparkles } from 'lucide-react'
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
import { Progress } from '@/components/ui/progress'
import { Skeleton } from '@/components/ui/skeleton'
import { StatusBadge } from '@/components/common/status-badge'
import { EVALUATION_GEN_JOB_STATUS_META } from '@/lib/status-meta'
import { parseJsonObject } from '@/lib/eval-metrics'
import type { EvalGenerationJob, EvalGenerationProgress } from '@/types/evaluation'

/**
 * 生成数据集 Modal（线框 09 §2.1）——唯一创建方式（EVAL-4：无手工创建/删除/重命名）。
 * 纯展示组件：任务轮询与终态收尾由父组件（dataset-list）持有，"后台生成"关闭后轮询不中断。
 */
export function GenerateDatasetDialog({
  open,
  onClose,
  onBackground,
  onSubmit,
  submitting,
  job,
  jobLoading,
}: {
  open: boolean
  /** 取消（未提交）/ 终态后关闭——父组件清理任务跟踪 */
  onClose: () => void
  /** 后台生成：关闭对话框但保留轮询（父组件不清理 jobId） */
  onBackground: () => void
  /** 提交表单（抛错由本组件 toast） */
  onSubmit: (name: string | undefined, userId: number) => Promise<void>
  submitting: boolean
  /** 进行中的生成任务（null=表单态） */
  job: EvalGenerationJob | null
  jobLoading: boolean
}) {
  const [name, setName] = useState('')
  const [userIdInput, setUserIdInput] = useState('')

  async function handleSubmit() {
    const userId = Number(userIdInput.trim())
    if (!Number.isInteger(userId) || userId <= 0) {
      toast.error('请填写有效的测试用户 ID（正整数）')
      return
    }
    try {
      await onSubmit(name.trim() || undefined, userId)
    } catch (e) {
      toast.error((e as Error).message)
    }
  }

  const progress: EvalGenerationProgress | null = parseJsonObject(job?.progressJson)
  const percent = progress && progress.total > 0 ? Math.round((progress.current / progress.total) * 100) : undefined
  const terminal = job?.status === 'completed' || job?.status === 'failed'

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        // Esc/X/遮罩关闭：运行中视为后台生成（保留轮询），表单/终态视为取消
        if (!next) {
          if (job != null && !terminal) onBackground()
          else onClose()
        }
      }}
    >
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>生成数据集</DialogTitle>
          <DialogDescription>
            从测试用户的知识库分块中 LLM 合成问答对（KG 多跳生成），生成后需人工审核条目。
          </DialogDescription>
        </DialogHeader>

        {job == null ? (
          <div className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="eval-gen-name">数据集名称</Label>
              <Input
                id="eval-gen-name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="缺省后端生成 dataset-<时间戳>"
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="eval-gen-user">
                测试用户 ID <span className="text-error-600">*</span>
              </Label>
              <Input
                id="eval-gen-user"
                value={userIdInput}
                onChange={(e) => setUserIdInput(e.target.value)}
                placeholder="采样该用户的知识库文档"
                inputMode="numeric"
              />
            </div>
          </div>
        ) : jobLoading && progress == null ? (
          <Skeleton className="h-20 w-full" />
        ) : (
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <StatusBadge meta={EVALUATION_GEN_JOB_STATUS_META[job.status]} />
              <span className="text-sm text-muted tabular-nums">
                {progress ? `${progress.current} / ${progress.total}` : ''}
              </span>
            </div>
            <Progress value={percent ?? 0} />
            <p className="text-xs text-muted">{progress?.message ?? '任务已提交，等待调度…（生成耗时数十秒级）'}</p>
          </div>
        )}

        <DialogFooter>
          {job == null ? (
            <>
              <Button variant="secondary" onClick={onClose}>取消</Button>
              <Button onClick={() => void handleSubmit()} loading={submitting}>
                <Sparkles className="size-4" /> 生成数据集
              </Button>
            </>
          ) : (
            <Button variant="secondary" onClick={terminal ? onClose : onBackground}>
              {terminal ? '关闭' : '后台生成'}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
