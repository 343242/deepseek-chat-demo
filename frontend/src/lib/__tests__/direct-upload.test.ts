import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ApiError } from '@/types/api'

// computeChecksum 走 worker（?worker 后缀），测试一律注入 checksumFn，模块加载也需 mock 掉
vi.mock('@/lib/checksum', () => ({ computeChecksum: vi.fn() }))
// upload-button 之外无需真实 api；defaultApi 引用真实实现，全部测试显式注入 mock api

import {
  uploadViaDirect, DirectNetworkError, partsStorageKey,
  type PutFn, type DirectUploadApi, type PartStore,
} from '@/lib/direct-upload'

const CHUNK = 5 * 1024 * 1024
const CHECKSUM = 'a'.repeat(64)

function mockFile(size: number): File {
  return new File([new ArrayBuffer(size)], 'report.pdf', { type: 'application/pdf' })
}

/** 记录调用并可控的 putFn：默认成功返回 etag */
function recordingPut() {
  const calls: { url: string; size: number }[] = []
  let maxInFlight = 0
  let inFlight = 0
  const putFn: PutFn = async (url, body) => {
    calls.push({ url, size: body.size })
    inFlight++
    maxInFlight = Math.max(maxInFlight, inFlight)
    await Promise.resolve()
    inFlight--
    return { etag: `etag-${url.match(/part=(\d+)/)?.[1] ?? 'single'}` }
  }
  return { putFn, calls, maxInFlight: () => maxInFlight }
}

function mockApi(initResults: Array<ReturnType<typeof vi.fn> extends never ? never : unknown>) {
  const api: DirectUploadApi = {
    init: vi.fn(),
    partUrls: vi.fn(async (_s: string, partNumbers: number[]) => ({
      urls: partNumbers.map((n) => ({ partNumber: n, url: `http://minio/put?part=${n}` })),
    })),
    commit: vi.fn(async () => ({ id: 42, fileName: 'report.pdf', status: 'PROCESSING' as const })),
    status: vi.fn(async () => ({ chunkSize: CHUNK, totalChunks: 1 })),
  }
  for (const r of initResults) (api.init as ReturnType<typeof vi.fn>).mockResolvedValueOnce(r)
  return api
}

function memoryStore(): PartStore & { data: Map<string, string> } {
  const data = new Map<string, string>()
  return {
    data,
    load: (_u, s) => (data.get(s) ? JSON.parse(data.get(s)!) : []),
    save: (_u, s, parts) => data.set(s, JSON.stringify(parts)),
    clear: (_u, s) => data.delete(s),
  }
}

const baseOpts = (file: File, api: DirectUploadApi, putFn: PutFn, store: PartStore) => ({
  file,
  userId: 7,
  teamId: null as number | null,
  putFn,
  checksumFn: async () => CHECKSUM,
  api,
  store,
})

describe('uploadViaDirect 编排', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('instant：init 秒传直接完成，无数据面 PUT', async () => {
    const api = mockApi([{ mode: 'instant', documentId: 42, fileName: 'report.pdf' }])
    const { putFn, calls } = recordingPut()
    const resp = await uploadViaDirect({ ...baseOpts(mockFile(10), api, putFn, memoryStore()) })
    expect(resp.id).toBe(42)
    expect(calls).toHaveLength(0)
    expect(api.commit).not.toHaveBeenCalled()
  })

  it('single：PUT uploadUrl（携带 contentType）→ commit 无 parts → 清理存储', async () => {
    const api = mockApi([{
      mode: 'single', sessionId: 'sess-s', uploadUrl: 'http://minio/single?sig=1',
      expiresAt: 1, contentType: 'application/pdf',
    }])
    const { putFn, calls } = recordingPut()
    const store = memoryStore()
    const resp = await uploadViaDirect({ ...baseOpts(mockFile(10), api, putFn, store) })
    expect(resp.id).toBe(42)
    expect(calls).toEqual([{ url: 'http://minio/single?sig=1', size: 10 }])
    expect(api.commit).toHaveBeenCalledWith('sess-s', {}, null)
  })

  it('multipart：12MB=3 片，分片 URL 批签、PUT 记录 ETag 到 sessionId 键、commit 回传有序 parts', async () => {
    const api = mockApi([{
      mode: 'multipart', sessionId: 'sess-m', uploadId: 'mpu-1',
      chunkSize: CHUNK, totalChunks: 3, expiresAt: 1,
    }])
    const { putFn, calls, maxInFlight } = recordingPut()
    const store = memoryStore()
    const savedSessions: string[] = []
    const origSave = store.save.bind(store)
    store.save = (u, s, parts) => {
      savedSessions.push(s)
      origSave(u, s, parts)
    }
    const file = mockFile(2 * CHUNK + 1024)
    const resp = await uploadViaDirect({ ...baseOpts(file, api, putFn, store) })

    expect(resp.id).toBe(42)
    expect(calls).toHaveLength(3)
    // 单批签发（3 ≤ 20）
    expect(api.partUrls).toHaveBeenCalledTimes(1)
    expect(api.partUrls).toHaveBeenCalledWith('sess-m', [1, 2, 3], null)
    // commit 回传 parts：number 有序、etag 来自 PUT、size 对拍（末片为余量）
    expect(api.commit).toHaveBeenCalledWith('sess-m', {
      parts: [
        { partNumber: 1, etag: 'etag-1', size: CHUNK },
        { partNumber: 2, etag: 'etag-2', size: CHUNK },
        { partNumber: 3, etag: 'etag-3', size: 1024 },
      ],
    }, null)
    // 上传过程中以 userId+sessionId 双维度键写入分片记录（防 ETag 串档；commit 成功后清理）
    expect(savedSessions.length).toBeGreaterThan(0)
    expect(savedSessions.every((s) => partsStorageKey(7, s) === partsStorageKey(7, 'sess-m'))).toBe(true)
    expect(store.data.has(partsStorageKey(7, 'sess-m'))).toBe(false)
    // 并发上限 ≤ UPLOAD_LIMITS.concurrentChunks(4)
    expect(maxInFlight()).toBeLessThanOrEqual(4)
  })

  it('断点续传：本地已记录 part 1/2，仅对缺失的 part 3 签发+PUT（本地差集）', async () => {
    const api = mockApi([{
      mode: 'multipart', sessionId: 'sess-r', uploadId: 'mpu-1',
      chunkSize: CHUNK, totalChunks: 3, expiresAt: 1,
    }])
    const { putFn, calls } = recordingPut()
    const store = memoryStore()
    store.save(7, 'sess-r', [
      { number: 1, etag: 'etag-1', size: CHUNK },
      { number: 2, etag: 'etag-2', size: CHUNK },
    ])
    const file = mockFile(2 * CHUNK + 1024)
    await uploadViaDirect({ ...baseOpts(file, api, putFn, store) })

    expect(calls).toHaveLength(1)
    expect(calls[0]?.url).toContain('part=3')
    expect(api.partUrls).toHaveBeenCalledWith('sess-r', [3], null)
  })

  it('commit 返回 UPLOAD_GONE(204016)：重新 init 后全量重传并 commit', async () => {
    const api = mockApi([
      { mode: 'multipart', sessionId: 'sess-gone', uploadId: 'mpu-1', chunkSize: CHUNK, totalChunks: 1, expiresAt: 1 },
      { mode: 'multipart', sessionId: 'sess-new', uploadId: 'mpu-2', chunkSize: CHUNK, totalChunks: 1, expiresAt: 1 },
    ])
    ;(api.commit as ReturnType<typeof vi.fn>)
      .mockRejectedValueOnce(new ApiError('直传会话已失效', 204016, 200))
      .mockResolvedValueOnce({ id: 43, fileName: 'report.pdf', status: 'PROCESSING' })
    const { putFn, calls } = recordingPut()
    const file = mockFile(1024) // 1 片
    const resp = await uploadViaDirect({ ...baseOpts(file, api, putFn, memoryStore()) })

    expect(resp.id).toBe(43)
    expect(calls).toHaveLength(2) // 旧会话 1 片 + 新会话 1 片
    expect(api.commit).toHaveBeenCalledTimes(2)
    expect(api.commit).toHaveBeenLastCalledWith('sess-new', {
      parts: [{ partNumber: 1, etag: 'etag-1', size: 1024 }],
    }, null)
  })

  it('init 429(100005)：指数退避重试后成功（backoffBaseMs=1 加速）', async () => {
    const api = mockApi([])
    ;(api.init as ReturnType<typeof vi.fn>)
      .mockRejectedValueOnce(new ApiError('请求过于频繁', 100005, 429))
      .mockRejectedValueOnce(new ApiError('请求过于频繁', 100005, 429))
      .mockResolvedValueOnce({ mode: 'single', sessionId: 's', uploadUrl: 'http://minio/x', expiresAt: 1, contentType: 'application/pdf' })
    const { putFn } = recordingPut()
    const resp = await uploadViaDirect({
      ...baseOpts(mockFile(10), api, putFn, memoryStore()),
      backoffBaseMs: 1,
    })
    expect(resp.id).toBe(42)
    expect(api.init).toHaveBeenCalledTimes(3)
  })

  it('数据面网络错误（XHR onerror）→ DirectNetworkError 上抛（调用方降级代理路径）', async () => {
    const api = mockApi([{
      mode: 'single', sessionId: 's', uploadUrl: 'http://minio/x', expiresAt: 1, contentType: 'application/pdf',
    }])
    const putFn: PutFn = async () => {
      throw new DirectNetworkError('直传网络错误（CORS/断网/入口限额）')
    }
    await expect(
      uploadViaDirect({ ...baseOpts(mockFile(10), api, putFn, memoryStore()) }),
    ).rejects.toBeInstanceOf(DirectNetworkError)
    expect(api.commit).not.toHaveBeenCalled()
  })

  it('commit 成功后清理本地分片记录', async () => {
    const api = mockApi([{
      mode: 'multipart', sessionId: 'sess-c', uploadId: 'mpu-1', chunkSize: CHUNK, totalChunks: 1, expiresAt: 1,
    }])
    const { putFn } = recordingPut()
    const store = memoryStore()
    await uploadViaDirect({ ...baseOpts(mockFile(1024), api, putFn, store) })
    expect(store.data.has(partsStorageKey(7, 'sess-c'))).toBe(false)
  })

  it('onSession 回调：init 建立会话时通知（取消等功能依赖）', async () => {
    const api = mockApi([{
      mode: 'multipart', sessionId: 'sess-cb', uploadId: 'mpu-1', chunkSize: CHUNK, totalChunks: 1, expiresAt: 1,
    }])
    const { putFn } = recordingPut()
    const onSession = vi.fn()
    await uploadViaDirect({ ...baseOpts(mockFile(1024), api, putFn, memoryStore()), onSession })
    expect(onSession).toHaveBeenCalledWith('sess-cb')
  })
})
