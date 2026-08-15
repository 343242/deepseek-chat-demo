import { useState } from 'react'
import { AlertTriangle, Trash2 } from 'lucide-react'
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'

/**
 * 破坏性确认弹窗（DS §10.11.1）—— 平台最关键交互模式
 * 必备：警示图标 + 动作名? + 点名对象/不可逆后果 + 取消(默认聚焦) + 确认(destructive 红色)
 * 遮罩点击不关闭（防误触）
 */
export interface ConfirmDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** 动作名，如"删除文档"。标题展示为"删除文档?" */
  title: string
  /** 后果说明（点名具体对象 + 不可逆） */
  description: React.ReactNode
  /** 确认按钮动词，如"删除" */
  confirmText?: string
  cancelText?: string
  /** 严重程度决定图标：danger→Trash2 error，warn→AlertTriangle warning */
  severity?: 'danger' | 'warn'
  onConfirm: () => void | Promise<void>
}

export function ConfirmDialog({
  open, onOpenChange, title, description, confirmText = '确认', cancelText = '取消', severity = 'danger', onConfirm,
}: ConfirmDialogProps) {
  const [loading, setLoading] = useState(false)
  const Icon = severity === 'danger' ? Trash2 : AlertTriangle
  const iconColor = severity === 'danger' ? 'text-error-600' : 'text-warning-600'

  async function handleConfirm() {
    try {
      setLoading(true)
      await onConfirm()
      onOpenChange(false)
    } finally {
      setLoading(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md" onPointerDownOutside={(e) => e.preventDefault()}>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Icon className={`size-5 ${iconColor}`} />
            {title}?
          </DialogTitle>
          <DialogDescription className="pt-1 text-subtle">{description}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="secondary" onClick={() => onOpenChange(false)} autoFocus disabled={loading}>
            {cancelText}
          </Button>
          <Button variant="destructive" onClick={() => void handleConfirm()} loading={loading}>
            {confirmText}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
