import { useState } from 'react'
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
import { Textarea } from '@/components/ui/textarea'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useUpdateItem } from '@/api/evaluation'
import { EVALUATION_ITEM_STATUS_META } from '@/lib/status-meta'
import type { EvalDatasetItem, EvalItemStatus } from '@/types/evaluation'

const STATUS_OPTIONS: EvalItemStatus[] = ['draft', 'approved', 'rejected']

/**
 * 条目审核编辑 Modal（线框 09 §2.2）——部分更新语义：仅提交改动字段，
 * 后端对 null/缺失字段保留旧值。
 * 表单回填走 useState 初始值 + 父组件 key={item.id} 重挂载（避免 effect 同步 setState）。
 */
export function ItemEditDialog({
  item,
  datasetId,
  onOpenChange,
}: {
  item: EvalDatasetItem
  datasetId: number
  onOpenChange: (open: boolean) => void
}) {
  const update = useUpdateItem(datasetId)
  const [question, setQuestion] = useState(item.question ?? '')
  const [answer, setAnswer] = useState(item.groundTruthAnswer ?? '')
  const [content, setContent] = useState(item.relevantContent ?? '')
  const [tagsInput, setTagsInput] = useState((item.tags ?? []).join(', '))
  const [status, setStatus] = useState<EvalItemStatus>(item.status ?? 'draft')

  async function handleSubmit() {
    if (item.id == null) return
    const tags = tagsInput.split(/[,，]/).map((t) => t.trim()).filter(Boolean)
    // 部分更新：只提交与原值不同的字段（null=保留旧值的后端契约）；请求 DTO readonly 故整体构造
    const updateBody = {
      ...(question !== (item.question ?? '') && { question }),
      ...(answer !== (item.groundTruthAnswer ?? '') && { groundTruthAnswer: answer }),
      ...(content !== (item.relevantContent ?? '') && { relevantContent: content }),
      ...(tags.join(',') !== (item.tags ?? []).join(',') && { tags }),
      ...(status !== (item.status ?? 'draft') && { status }),
    }

    if (Object.keys(updateBody).length === 0) {
      onOpenChange(false)
      return
    }
    try {
      await update.mutateAsync({ itemId: item.id, update: updateBody })
      toast.success('条目已更新')
      onOpenChange(false)
    } catch (e) {
      toast.error((e as Error).message)
    }
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>审核条目 #{item.seq}</DialogTitle>
          <DialogDescription>人工修正 LLM 合成的问答对；未改动的字段不提交（保留旧值）。</DialogDescription>
        </DialogHeader>

        <div className="max-h-[60vh] space-y-4 overflow-y-auto pr-1">
          <div className="space-y-1.5">
            <Label htmlFor="eval-item-question">问题</Label>
            <Textarea id="eval-item-question" value={question} onChange={(e) => setQuestion(e.target.value)} rows={2} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="eval-item-answer">标准答案</Label>
            <Textarea id="eval-item-answer" value={answer} onChange={(e) => setAnswer(e.target.value)} rows={4} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="eval-item-content">参考内容（可选）</Label>
            <Textarea
              id="eval-item-content"
              value={content}
              onChange={(e) => setContent(e.target.value)}
              rows={4}
              className="font-mono text-xs"
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="eval-item-tags">标签（逗号分隔）</Label>
            <Input id="eval-item-tags" value={tagsInput} onChange={(e) => setTagsInput(e.target.value)} placeholder="多跳, 具体" />
          </div>
          <div className="space-y-1.5">
            <Label>审核状态</Label>
            <Select value={status} onValueChange={(v) => setStatus(v as EvalItemStatus)}>
              <SelectTrigger className="w-36">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {STATUS_OPTIONS.map((s) => (
                  <SelectItem key={s} value={s}>{EVALUATION_ITEM_STATUS_META[s].label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>

        <DialogFooter>
          <Button variant="secondary" onClick={() => onOpenChange(false)}>取消</Button>
          <Button onClick={() => void handleSubmit()} loading={update.isPending}>保存</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
