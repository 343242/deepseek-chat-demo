import { useState, useRef, useCallback } from 'react'
import { Upload, X, FileUp } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Progress } from '@/components/ui/progress'
import { toast } from 'sonner'
import {
  uploadDirect, shouldChunk, chunkUploadInit, uploadChunk, chunkUploadComplete, chunkUploadDelete,
} from '@/api/documents'
import { queryClient } from '@/lib/query-client'
import { md5 } from '@/lib/md5'
import { UPLOAD_LIMITS } from '@/lib/constants'
import { extOf, formatFileSize, formatSpeed } from '@/lib/format'
import { cn } from '@/lib/utils'
import { FileTypeIcon } from './file-icon'

interface UploadTask {
  id: string
  fileName: string
  fileSize: number
  status: 'uploading' | 'success' | 'error' | 'instant'
  progress: number // 0-100
  speed?: number
  startedAt: number
  lastBytes: number
  lastTime: number
  uploadId?: string
  error?: string
}

export function UploadButton({
  teamId,
  replaceDocumentId,
  onDone,
  compact,
}: {
  teamId?: number | null
  replaceDocumentId?: number
  onDone?: () => void
  compact?: boolean
}) {
  const [tasks, setTasks] = useState<UploadTask[]>([])
  const [dragOver, setDragOver] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  const validate = (file: File): string | null => {
    if (!(UPLOAD_LIMITS.allowedExtensions as readonly string[]).includes(extOf(file.name))) {
      return '不支持的文件格式，仅支持 PDF/DOCX/PPTX/XLSX/TXT/MD/HTML'
    }
    if (file.size > UPLOAD_LIMITS.maxSize) return '文件超过 50MB 限制'
    return null
  }

  const handleFiles = useCallback(
    async (files: FileList | File[]) => {
      const list = Array.from(files).slice(0, UPLOAD_LIMITS.maxBatch)
      for (const file of list) {
        const err = validate(file)
        if (err) {
          toast.error(err)
          continue
        }
        const taskId = crypto.randomUUID()
        const base: UploadTask = {
          id: taskId, fileName: file.name, fileSize: file.size,
          status: 'uploading', progress: 0, startedAt: Date.now(),
          lastBytes: 0, lastTime: Date.now(),
        }
        setTasks((t) => [base, ...t])
        try {
          if (!shouldChunk(file.size)) {
            // 小文件直传
            await uploadDirect(file, teamId)
            setTasks((t) => t.map((x) => (x.id === taskId ? { ...x, progress: 100, status: 'success' } : x)))
          } else {
            // 分片上传
            const buf = await file.arrayBuffer()
            const fileMd5 = md5(buf)
            const chunkSize = UPLOAD_LIMITS.chunkSize
            const totalChunks = Math.ceil(file.size / chunkSize)
            const init = await chunkUploadInit({
              fileMd5, fileName: file.name, fileSize: file.size, mimeType: file.type, chunkSize,
              teamId: teamId ?? null, replaceDocumentId: replaceDocumentId ?? null,
            })
            if (init.uploaded) {
              // 秒传
              setTasks((t) => t.map((x) => (x.id === taskId ? { ...x, progress: 100, status: 'instant' } : x)))
              toast.success(`${file.name} 秒传成功`)
            } else {
              const uploadId = init.uploadId!
              for (let i = 0; i < totalChunks; i++) {
                const start = i * chunkSize
                const blob = file.slice(start, Math.min(start + chunkSize, file.size))
                const chunkMd5 = md5(await blob.arrayBuffer())
                await uploadChunk(uploadId, i, blob, chunkMd5)
                const progress = Math.round(((i + 1) / totalChunks) * 100)
                const now = Date.now()
                const uploadedBytes = (i + 1) * chunkSize
                const speed = (uploadedBytes - base.lastBytes) / ((now - base.lastTime) / 1000)
                setTasks((t) => t.map((x) => (x.id === taskId ? { ...x, progress, speed, lastBytes: uploadedBytes, lastTime: now, uploadId } : x)))
              }
              await chunkUploadComplete(uploadId)
              setTasks((t) => t.map((x) => (x.id === taskId ? { ...x, progress: 100, status: 'success' } : x)))
            }
          }
          queryClient.invalidateQueries({ queryKey: ['documents'] })
          onDone?.()
        } catch (e) {
          setTasks((t) => t.map((x) => (x.id === taskId ? { ...x, status: 'error', error: (e as Error).message } : x)))
          toast.error(`${file.name} 上传失败`)
        }
      }
    },
    [teamId, replaceDocumentId, onDone],
  )

  function cancel(task: UploadTask) {
    if (task.uploadId) void chunkUploadDelete(task.uploadId).catch(() => {})
    setTasks((t) => t.filter((x) => x.id !== task.id))
  }

  return (
    <div
      onDragOver={(e) => { e.preventDefault(); setDragOver(true) }}
      onDragLeave={() => setDragOver(false)}
      onDrop={(e) => { e.preventDefault(); setDragOver(false); if (e.dataTransfer.files.length) void handleFiles(e.dataTransfer.files) }}
      className={cn('relative', dragOver && 'ring-2 ring-primary-600 ring-offset-2 rounded-lg')}
    >
      {dragOver && (
        <div className="absolute inset-0 z-10 flex items-center justify-center rounded-lg bg-primary-50/90">
          <div className="flex flex-col items-center gap-1 text-primary-700">
            <FileUp className="size-8" />
            <span className="text-sm font-medium">释放以上传</span>
            <span className="text-xs text-subtle">PDF/DOCX/PPTX/XLSX/TXT/MD/HTML · 单文件 ≤ 50MB</span>
          </div>
        </div>
      )}

      <div className={cn('flex items-center gap-3', !compact && 'mb-4')}>
        <Button onClick={() => inputRef.current?.click()}>
          <Upload className="size-4" /> 上传文档
        </Button>
        <input
          ref={inputRef}
          type="file"
          multiple
          className="hidden"
          accept={UPLOAD_LIMITS.allowedExtensions.map((e) => '.' + e).join(',')}
          onChange={(e) => { if (e.target.files?.length) void handleFiles(e.target.files); e.target.value = '' }}
        />
        {!compact && <span className="text-xs text-subtle">支持拖拽上传到本区域</span>}
      </div>

      {/* 上传进度卡 */}
      {tasks.length > 0 && (
        <div className="mb-4 space-y-2">
          {tasks.map((task) => (
            <ProgressCard key={task.id} task={task} onCancel={cancel} />
          ))}
        </div>
      )}
    </div>
  )
}

function ProgressCard({ task, onCancel }: { task: UploadTask; onCancel: (t: UploadTask) => void }) {
  const eta = task.speed && task.speed > 0 ? Math.ceil((task.fileSize * (1 - task.progress / 100)) / task.speed) : 0
  return (
    <div className="flex items-center gap-3 rounded-lg border border-line-subtle bg-surface px-3 py-2.5">
      <FileTypeIcon fileName={task.fileName} className="size-5 shrink-0" />
      <div className="min-w-0 flex-1">
        <div className="flex items-center justify-between gap-2">
          <span className="truncate text-sm font-medium text-fg">{task.fileName}</span>
          <span className="shrink-0 text-xs text-subtle">
            {task.status === 'success' ? '完成' : task.status === 'instant' ? '秒传' : task.status === 'error' ? '失败' : `${task.progress}%`}
          </span>
        </div>
        <Progress value={task.progress} className={cn('mt-1.5 h-1.5', task.status === 'error' && '[&_[data-slot]]:bg-error-600')} />
        <div className="mt-1 flex items-center justify-between text-xs text-subtle">
          <span>
            {formatFileSize(task.fileSize)}
            {task.status === 'uploading' && task.speed ? ` · ${formatSpeed(task.speed)}` : ''}
            {task.status === 'uploading' && eta ? ` · 剩余 ${eta}s` : ''}
            {task.status === 'error' && task.error ? ` · ${task.error}` : ''}
          </span>
          {task.status === 'uploading' && (
            <button onClick={() => onCancel(task)} className="text-subtle hover:text-error-600" aria-label="取消">
              <X className="size-3.5" />
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
