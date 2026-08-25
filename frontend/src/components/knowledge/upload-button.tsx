import { useState, useRef, useCallback, useEffect } from 'react'
import { Upload, X, FileUp } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Progress } from '@/components/ui/progress'
import { toast } from 'sonner'
import {
  uploadDirect, uploadBatch, shouldChunk, chunkUploadInit, uploadChunk, chunkUploadComplete, chunkUploadDelete,
  fetchDirectUploadConfig, directUploadAbort,
  docKeys,
} from '@/api/documents'
import { queryClient } from '@/lib/query-client'
import { computeChecksum } from '@/lib/checksum'
import { UPLOAD_LIMITS } from '@/lib/constants'
import { uploadViaDirect, DirectNetworkError } from '@/lib/direct-upload'
import { useAuthStore } from '@/stores/auth-store'
import { extOf, formatFileSize, formatSpeed } from '@/lib/format'
import { cn } from '@/lib/utils'
import { FileTypeIcon } from './file-icon'

/** 扩展名白名单（Set 成员判定，消除 as const 元组 × includes 的变差断言，FE-020） */
const ALLOWED_EXTENSIONS = new Set<string>(UPLOAD_LIMITS.allowedExtensions)

/** 直传灰度开关：进程内缓存一次（GET /config）；请求失败按 false 处理走代理路径 */
let directEnabledPromise: Promise<boolean> | null = null
function directUploadEnabled(): Promise<boolean> {
  directEnabledPromise ??= fetchDirectUploadConfig()
    .then((c) => c.enabled)
    .catch(() => false)
  return directEnabledPromise
}

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
  /** uploadId 为 direct-uploads 会话（取消走 directUploadAbort） */
  direct?: boolean
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

  // 上传成功后短暂保留"完成"态作为反馈，随后自动移除进度卡——文件已出现在列表，避免冗余视觉。
  // 失败 / 上传中的卡片不在移除范围（保留给用户查看 / 取消）。
  const dismissTimers = useRef<Set<ReturnType<typeof setTimeout>>>(new Set())
  const scheduleDismiss = useCallback((taskId: string) => {
    const id = setTimeout(() => {
      dismissTimers.current.delete(id)
      setTasks((t) => t.filter((x) => x.id !== taskId))
    }, 1500)
    dismissTimers.current.add(id)
  }, [])
  useEffect(() => () => {
    dismissTimers.current.forEach((t) => clearTimeout(t))
  }, [])

  const validate = (file: File): string | null => {
    if (!ALLOWED_EXTENSIONS.has(extOf(file.name))) {
      return '不支持的文件格式，仅支持 PDF/DOCX/PPTX/XLSX/TXT/MD/HTML'
    }
    if (file.size > UPLOAD_LIMITS.maxSize) return '文件超过 50MB 限制'
    return null
  }

  const handleFiles = useCallback(
    async (files: FileList | File[]) => {
      const all = Array.from(files)
      if (all.length > UPLOAD_LIMITS.maxBatch) {
        toast.warning(`一次最多上传 ${UPLOAD_LIMITS.maxBatch} 个文件，已忽略超出的 ${all.length - UPLOAD_LIMITS.maxBatch} 个`)
      }
      const valid: File[] = []
      for (const file of all.slice(0, UPLOAD_LIMITS.maxBatch)) {
        const err = validate(file)
        if (err) toast.error(err)
        else valid.push(file)
      }
      if (valid.length === 0) return

      const makeTask = (file: File): string => {
        const taskId = crypto.randomUUID()
        setTasks((t) => [{
          id: taskId, fileName: file.name, fileSize: file.size,
          status: 'uploading', progress: 0, startedAt: Date.now(),
          lastBytes: 0, lastTime: Date.now(),
        }, ...t])
        return taskId
      }
      const markSuccess = (taskId: string) => {
        setTasks((t) => t.map((x) => (x.id === taskId ? { ...x, progress: 100, status: 'success' } : x)))
        scheduleDismiss(taskId)
      }
      const markError = (taskId: string, message: string, fileName: string) => {
        setTasks((t) => t.map((x) => (x.id === taskId ? { ...x, status: 'error', error: message } : x)))
        toast.error(`${fileName} 上传失败`)
      }

      // 分片上传（断点续传）：秒传 / 逐片上传 / complete 兜底，成功态由内部标记
      const uploadViaChunks = async (file: File, taskId: string) => {
        const fileChecksum = await computeChecksum(file)
        const chunkSize = UPLOAD_LIMITS.chunkSize
        const totalChunks = Math.ceil(file.size / chunkSize)
        const init = await chunkUploadInit({
          fileChecksum, fileName: file.name, fileSize: file.size, mimeType: file.type, chunkSize,
          teamId: teamId ?? null, replaceDocumentId: replaceDocumentId ?? null,
        })
        if (init.uploaded) {
          // 秒传
          setTasks((t) => t.map((x) => (x.id === taskId ? { ...x, progress: 100, status: 'instant' } : x)))
          toast.success(`${file.name} 秒传成功`)
          scheduleDismiss(taskId)
          return
        }
        const uploadId = init.uploadId!
        const startedAt = Date.now()
        for (let i = 0; i < totalChunks; i++) {
          const start = i * chunkSize
          const blob = file.slice(start, Math.min(start + chunkSize, file.size))
          const chunkChecksum = await computeChecksum(blob)
          await uploadChunk(uploadId, i, blob, chunkChecksum)
          const progress = Math.round(((i + 1) / totalChunks) * 100)
          const now = Date.now()
          const uploadedBytes = (i + 1) * chunkSize
          const speed = uploadedBytes / ((now - startedAt) / 1000)
          setTasks((t) => t.map((x) => (x.id === taskId ? { ...x, progress, speed, lastBytes: uploadedBytes, lastTime: now, uploadId } : x)))
        }
        // 最后一片上传后，后端 Lua 脚本已自动触发异步合并（performMerge → 落库 → ETL dispatch）。
        // 文档状态变化由 SSE 实时推送（见 knowledge-page.tsx），前端无需等待 complete 结果。
        // complete 仅作"自动合并未触发"的兜底，fire-and-forget，不影响上传成功状态。
        setTasks((t) => t.map((x) => (x.id === taskId ? { ...x, progress: 100, status: 'success' } : x)))
        scheduleDismiss(taskId)
        void chunkUploadComplete(uploadId, fileChecksum).catch(() => {})
      }

      // 单文件直传 / 分片（"上传新版本"场景：批量端点不支持 replaceDocumentId，只能逐文件）
      const uploadOne = async (file: File) => {
        const taskId = makeTask(file)
        try {
          if (!shouldChunk(file.size)) {
            await uploadDirect(file, teamId, replaceDocumentId)
            markSuccess(taskId)
          } else {
            await uploadViaChunks(file, taskId)
          }
          void queryClient.invalidateQueries({ queryKey: docKeys.all })
          onDone?.()
        } catch (e) {
          markError(taskId, (e as Error).message, file.name)
        }
      }

      /* ---- Presigned 直传（灰度 flag 开启时）：单/分片统一走 direct-uploads 会话，
       * 网络层失败（CORS 预检失败/断网/入口 413）自动降级既有代理路径（阶段 2 行为）。
       * 批量在 direct 模式下消解为逐文件会话（init 429 由编排层指数退避）。 */
      if (await directUploadEnabled()) {
        const userId = useAuthStore.getState().user?.id
        if (userId != null) {
          for (const file of valid) {
            const taskId = makeTask(file)
            const startedAt = Date.now()
            try {
              const resp = await uploadViaDirect({
                file, userId, teamId, replaceDocumentId,
                onSession: (sessionId) => {
                  setTasks((t) => t.map((x) => (x.id === taskId ? { ...x, uploadId: sessionId, direct: true } : x)))
                },
                onProgress: (uploadedBytes) => {
                  const progress = Math.min(100, Math.round((uploadedBytes / file.size) * 100))
                  const speed = uploadedBytes / ((Date.now() - startedAt) / 1000)
                  setTasks((t) => t.map((x) => (x.id === taskId ? { ...x, progress, speed } : x)))
                },
              })
              void resp
              markSuccess(taskId)
              void queryClient.invalidateQueries({ queryKey: docKeys.all })
              onDone?.()
            } catch (e) {
              if (e instanceof DirectNetworkError) {
                // 降级：代理路径完整接管该文件（进度卡复用，错误态只在两条路径都失败时呈现）
                try {
                  if (!shouldChunk(file.size)) await uploadDirect(file, teamId, replaceDocumentId)
                  else await uploadViaChunks(file, taskId)
                  markSuccess(taskId)
                  void queryClient.invalidateQueries({ queryKey: docKeys.all })
                  onDone?.()
                } catch (fallbackErr) {
                  markError(taskId, (fallbackErr as Error).message, file.name)
                }
              } else {
                markError(taskId, (e as Error).message, file.name)
              }
            }
          }
          return
        }
        // userId 缺失（未就绪）：落到代理路径
      }

      if (replaceDocumentId) {
        for (const file of valid) await uploadOne(file)
        return
      }

      // 小文件（≤ chunkThreshold）合并为一次批量请求；无字节级进度，0→100 跳变与原直传一致
      const small = valid.filter((f) => !shouldChunk(f.size))
      if (small.length > 0) {
        const batchTasks = small.map((file) => ({ file, taskId: makeTask(file) }))
        try {
          const results = await uploadBatch(small, teamId)
          if (results.length === batchTasks.length) {
            // 响应与输入顺序一一对应（后端策略按输入顺序逐文件产出）；部分失败项 status=FAILED
            batchTasks.forEach(({ file, taskId }, i) => {
              if (results[i]?.status === 'FAILED') markError(taskId, '上传失败', file.name)
              else markSuccess(taskId)
            })
          } else {
            // 长度不符（契约异常）：按整批成功兜底，失败由 SSE 状态流纠正
            batchTasks.forEach(({ taskId }) => markSuccess(taskId))
          }
          void queryClient.invalidateQueries({ queryKey: docKeys.all })
          onDone?.()
        } catch (e) {
          const message = (e as Error).message
          batchTasks.forEach(({ file, taskId }) => markError(taskId, message, file.name))
        }
      }

      // 大文件逐个分片（断点续传天然按文件进行）
      for (const file of valid.filter((f) => shouldChunk(f.size))) {
        await uploadOne(file)
      }
    },
    [teamId, replaceDocumentId, onDone, scheduleDismiss],
  )

  function cancel(task: UploadTask) {
    if (task.uploadId) {
      if (task.direct) void directUploadAbort(task.uploadId, teamId).catch(() => {})
      else void chunkUploadDelete(task.uploadId).catch(() => {})
    }
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
    <div className="animate-in fade-in slide-in-from-top-2 duration-200 flex items-center gap-3 rounded-lg border border-line-subtle bg-surface px-3 py-2.5">
      <FileTypeIcon fileName={task.fileName} className="size-5 shrink-0" />
      <div className="min-w-0 flex-1">
        <div className="flex items-center justify-between gap-2">
          <span className="truncate text-sm font-medium text-fg">{task.fileName}</span>
          <span className="shrink-0 text-xs text-subtle">
            {task.status === 'success' ? '已上传' : task.status === 'instant' ? '秒传' : task.status === 'error' ? '失败' : `${task.progress}%`}
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
