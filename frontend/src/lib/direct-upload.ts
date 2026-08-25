/**
 * Presigned 直传编排（设计文档：docs/design/presigned-direct-upload.md「前端改造」）。
 *
 * 流程：computeChecksum → init（instant 直接完成）→
 *   single: XHR PUT uploadUrl（字节级进度）→ commit
 *   multipart: 本地差集 → part-urls 批量签发（单批 ≤20）→ 4 并发 XHR PUT →
 *              每片记录 {number, etag, size}（localStorage，键含 sessionId 防 ETag 串档）→
 *              全部到位 → commit（回传 parts；UPLOAD_GONE → 重新 init）
 *
 * 依赖全部可注入（putFn/checksumFn/api/storage），vitest 用 mock XHR 覆盖并发/续传/退避。
 */
import { computeChecksum } from '@/lib/checksum'
import {
  directUploadInit, directUploadPartUrls, directUploadCommit, directUploadStatus,
} from '@/api/documents'
import { UPLOAD_LIMITS } from '@/lib/constants'
import type {
  DirectUploadInitResult, DirectUploadPartDeclaration, DocumentUploadResponse,
} from '@/types/document'
import { ApiError } from '@/types/api'

/** 后端错误码：直传会话已失效（204016，引导重新 init） */
export const DIRECT_UPLOAD_GONE = 204016
/** 后端错误码：init 限流（100005，批量 init 指数退避） */
export const RATE_LIMITED = 100005

/** 直传网络层错误（CORS 预检失败/断网/413 等）——调用方据此降级代理路径 */
export class DirectNetworkError extends Error {
  constructor(message: string, readonly cause?: unknown) {
    super(message)
    this.name = 'DirectNetworkError'
  }
}

/** PUT 结果：UploadPart/单文件 PUT 响应的 ETag（multipart commit 回传依赖） */
export interface PutResult { etag: string }

/**
 * 数据面 PUT：XHR（fetch 无 upload progress）；可注入测试。
 * Content-Type 在 presign 中未签名，仅 single 模式按 init 返回值携带。
 */
export type PutFn = (
  url: string,
  body: Blob,
  contentType: string | undefined,
  onProgress: (loadedBytes: number) => void,
) => Promise<PutResult>

/** 默认 XHR 实现：404/403 等数据面异常视为网络层错误（触发降级），其余透传 ETag */
export const xhrPut: PutFn = (url, body, contentType, onProgress) =>
  new Promise<PutResult>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', url)
    if (contentType) xhr.setRequestHeader('Content-Type', contentType)
    xhr.upload.onprogress = (e) => onProgress(e.loaded)
    xhr.onerror = () => reject(new DirectNetworkError('直传网络错误（CORS/断网/入口限额）'))
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve({ etag: (xhr.getResponseHeader('ETag') ?? '').replace(/^"|"$/g, '') })
      } else {
        // 404 NoSuchUpload（MPU 已消亡）等：网络层错误处理，由编排层重新 init
        reject(new DirectNetworkError(`直传 PUT 失败: HTTP ${xhr.status}`))
      }
    }
    xhr.send(body)
  })

/** 控制面 API 形状（默认真实实现，测试注入 mock） */
export interface DirectUploadApi {
  init(req: Parameters<typeof directUploadInit>[0], teamId?: number | null): Promise<DirectUploadInitResult>
  partUrls(sessionId: string, partNumbers: number[], teamId?: number | null): Promise<{ urls: { partNumber: number; url: string }[] }>
  commit(sessionId: string, body: { parts?: DirectUploadPartDeclaration[] | null }, teamId?: number | null): Promise<DocumentUploadResponse>
  status(sessionId: string, teamId?: number | null): Promise<{ chunkSize: number; totalChunks: number; uploadId?: string | null }>
}

const defaultApi: DirectUploadApi = {
  init: directUploadInit,
  partUrls: directUploadPartUrls,
  commit: directUploadCommit,
  status: directUploadStatus,
}

/** 本地分片记录（PUT 成功即记；断点续传差集 + commit 回传） */
interface PartRecord { number: number; etag: string; size: number }

export interface PartStore {
  load(userId: number, sessionId: string): PartRecord[]
  save(userId: number, sessionId: string, parts: PartRecord[]): void
  clear(userId: number, sessionId: string): void
}

/** localStorage 键必须含 userId+sessionId 双维度（防同用户同 checksum 文件间 ETag 串档） */
export function partsStorageKey(userId: number, sessionId: string): string {
  return `direct-upload:parts:${userId}:${sessionId}`
}

const localStorageStore: PartStore = {
  load(userId, sessionId) {
    try {
      const raw = localStorage.getItem(partsStorageKey(userId, sessionId))
      return raw ? (JSON.parse(raw) as PartRecord[]) : []
    } catch {
      return []
    }
  },
  save(userId, sessionId, parts) {
    try {
      localStorage.setItem(partsStorageKey(userId, sessionId), JSON.stringify(parts))
    } catch {
      // 配额满等：放弃本地续传记录（退化为全量重传，无正确性问题）
    }
  },
  clear(userId, sessionId) {
    try {
      localStorage.removeItem(partsStorageKey(userId, sessionId))
    } catch {
      /* 忽略 */
    }
  },
}

export interface DirectUploadOptions {
  file: File
  userId: number
  teamId?: number | null
  replaceDocumentId?: number | null
  onProgress?: (uploadedBytes: number) => void
  /** 取消信号（true 后中断等待中的批次） */
  shouldStop?: () => boolean
  /** 会话建立回调（init 返回 sessionId 时触发；调用方用于取消等功能） */
  onSession?: (sessionId: string) => void
  /** 测试注入点 */
  putFn?: PutFn
  checksumFn?: (blob: Blob) => Promise<string>
  api?: DirectUploadApi
  store?: PartStore
  /** init 429 退避基础延迟（测试缩短） */
  backoffBaseMs?: number
}

/** 单文件直传入口；instant/上传完成返回 DocumentUploadResponse */
export async function uploadViaDirect(opts: DirectUploadOptions): Promise<DocumentUploadResponse> {
  const {
    file, userId, teamId = null, replaceDocumentId = null, onProgress,
    putFn = xhrPut, checksumFn = computeChecksum, api = defaultApi,
    store = localStorageStore, backoffBaseMs = 1000,
  } = opts
  const checksum = await checksumFn(file)
  onProgress?.(0)
  const announceSession = (sessionId: string) => opts.onSession?.(sessionId)

  const init = await initWithBackoff(() => api.init({
    fileName: file.name, fileSize: file.size, mimeType: file.type || 'application/octet-stream',
    fileChecksum: checksum, teamId, replaceDocumentId,
  }, teamId), backoffBaseMs)

  if (init.mode === 'instant') {
    onProgress?.(file.size)
    return { id: init.documentId ?? null, fileName: init.fileName ?? file.name, status: 'PROCESSING' }
  }
  if (init.mode === 'single') {
    const sessionId = init.sessionId!
    announceSession(sessionId)
    await putFn(init.uploadUrl!, file, init.contentType, onProgress)
    return await commitSession(sessionId, [], { userId, teamId, api, store })
  }

  // multipart：一次 UPLOAD_GONE 全量重来（本地记录作废，同 key 同 partNumber 覆盖无正确性问题）
  const sessionId = init.sessionId!
  announceSession(sessionId)
  try {
    return await uploadPartsAndCommit(sessionId, init, { file, userId, teamId, putFn, api, store, checksum, onProgress, shouldStop: opts.shouldStop })
  } catch (e) {
    if (e instanceof ApiError && e.code === DIRECT_UPLOAD_GONE) {
      const retry = await initWithBackoff(() => api.init({
        fileName: file.name, fileSize: file.size, mimeType: file.type || 'application/octet-stream',
        fileChecksum: checksum, teamId, replaceDocumentId,
      }, teamId), backoffBaseMs)
      if (retry.mode === 'instant') {
        onProgress?.(file.size)
        return { id: retry.documentId ?? null, fileName: retry.fileName ?? file.name, status: 'PROCESSING' }
      }
      announceSession(retry.sessionId!)
      if (retry.mode === 'multipart') {
        return await uploadPartsAndCommit(retry.sessionId!, retry, { file, userId, teamId, putFn, api, store, checksum, onProgress, shouldStop: opts.shouldStop })
      }
      await putFn(retry.uploadUrl!, file, retry.contentType, onProgress)
      return await commitSession(retry.sessionId!, [], { userId, teamId, api, store })
    }
    throw e
  }
}

/** init 指数退避（1s/2s/4s，共 3 次重试）：批量 10 文件并发 init 触发 30 次/分限流时消化 429 */
async function initWithBackoff(initFn: () => Promise<DirectUploadInitResult>, baseMs: number): Promise<DirectUploadInitResult> {
  let attempt = 0
  for (;;) {
    try {
      return await initFn()
    } catch (e) {
      if (e instanceof ApiError && e.code === RATE_LIMITED && attempt < 3) {
        await sleep(baseMs * 2 ** attempt)
        attempt++
        continue
      }
      throw e
    }
  }
}

async function uploadPartsAndCommit(
  sessionId: string,
  init: DirectUploadInitResult,
  ctx: {
    file: File; userId: number; teamId?: number | null
    putFn: PutFn; api: DirectUploadApi; store: PartStore
    checksum: string; onProgress?: (b: number) => void
    shouldStop?: () => boolean
  },
): Promise<DocumentUploadResponse> {
  const { file, userId, teamId, putFn, api, store, checksum, onProgress, shouldStop } = ctx
  const chunkSize = init.chunkSize ?? UPLOAD_LIMITS.chunkSize
  const totalChunks = init.totalChunks ?? Math.ceil(file.size / chunkSize)

  // 本地差集：已记录分片不重传（换设备/清缓存退化为全量重传）
  const recorded = new Map(store.load(userId, sessionId).map((p) => [p.number, p]))
  const missing: number[] = []
  for (let n = 1; n <= totalChunks; n++) {
    if (!recorded.has(n)) missing.push(n)
  }

  let uploadedBytes = totalChunks > 0 ? file.size - missing.length * chunkSize : 0
  if (uploadedBytes < 0) uploadedBytes = 0
  if (uploadedBytes > file.size) uploadedBytes = file.size
  onProgress?.(uploadedBytes)

  // 单批 ≤20（后端硬校验）；4 并发 XHR PUT（UPLOAD_LIMITS.concurrentChunks）
  for (let i = 0; i < missing.length; i += 20) {
    if (shouldStop?.()) throw new DirectNetworkError('已取消')
    const batch = missing.slice(i, i + 20)
    const { urls } = await api.partUrls(sessionId, batch, teamId)
    await runPool(urls, UPLOAD_LIMITS.concurrentChunks, async ({ partNumber, url }) => {
      const start = (partNumber - 1) * chunkSize
      const blob = file.slice(start, Math.min(start + chunkSize, file.size))
      const { etag } = await putFn(url, blob, undefined, (loaded) => {
        onProgress?.(uploadedBytes - blob.size + loaded)
      })
      const part: PartRecord = { number: partNumber, etag, size: blob.size }
      recorded.set(partNumber, part)
      store.save(userId, sessionId, [...recorded.values()].sort((a, b) => a.number - b.number))
      uploadedBytes += blob.size
      onProgress?.(Math.min(uploadedBytes, file.size))
    })
  }

  const parts = [...recorded.values()]
    .filter((p) => p.number >= 1 && p.number <= totalChunks)
    .sort((a, b) => a.number - b.number)
    .map((p) => ({ partNumber: p.number, etag: p.etag, size: p.size }))
  return await commitSession(sessionId, parts, { userId, teamId, api, store })
}

/** commit + 成功后清理本地分片记录（失败保留供续传；GONE 上抛由调用方重新 init） */
async function commitSession(
  sessionId: string,
  parts: DirectUploadPartDeclaration[],
  ctx: { userId: number; teamId?: number | null; api: DirectUploadApi; store: PartStore },
): Promise<DocumentUploadResponse> {
  const resp = await ctx.api.commit(sessionId, parts.length > 0 ? { parts } : {}, ctx.teamId)
  ctx.store.clear(ctx.userId, sessionId)
  return resp
}

/** 固定并发池（避免 N 片全并发压垮浏览器连接池） */
async function runPool<T>(items: T[], concurrency: number, worker: (item: T) => Promise<void>): Promise<void> {
  let next = 0
  const runners = Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    for (;;) {
      const idx = next++
      if (idx >= items.length) return
      await worker(items[idx])
    }
  })
  await Promise.all(runners)
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms))
}
