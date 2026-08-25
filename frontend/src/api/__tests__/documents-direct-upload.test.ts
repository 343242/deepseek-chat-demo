import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('@/lib/api-fetch', () => ({
  apiFetch: vi.fn(),
  api: {
    get: vi.fn(),
    post: vi.fn().mockResolvedValue(undefined),
    put: vi.fn(),
    del: vi.fn(),
  },
}))

import { api } from '@/lib/api-fetch'
import {
  directUploadInit,
  directUploadPartUrls,
  directUploadStatus,
  directUploadCommit,
  directUploadAbort,
} from '@/api/documents'

const mockedApi = vi.mocked(api)

describe('直传 API 封装（DirectUploadController）', () => {
  beforeEach(() => {
    vi.mocked(mockedApi.get).mockReset().mockResolvedValue({ enabled: false })
    vi.mocked(mockedApi.post).mockReset().mockResolvedValue(undefined)
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('init：个人走 /documents/direct-uploads，请求体含元数据', async () => {
    await directUploadInit(
      { fileName: 'a.pdf', fileSize: 10, mimeType: 'application/pdf', fileChecksum: 'c'.repeat(64) },
      null,
    )
    expect(mockedApi.post).toHaveBeenCalledWith('/documents/direct-uploads', {
      fileName: 'a.pdf', fileSize: 10, mimeType: 'application/pdf', fileChecksum: 'c'.repeat(64),
    })
  })

  it('init：团队走 /teams/{teamId}/documents/direct-uploads', async () => {
    await directUploadInit(
      { fileName: 'a.pdf', fileSize: 10, mimeType: 'application/pdf', fileChecksum: 'c'.repeat(64) },
      3,
    )
    expect(mockedApi.post).toHaveBeenCalledWith('/teams/3/documents/direct-uploads', expect.anything())
  })

  it('part-urls：POST .../{sessionId}/part-urls，body 为 partNumbers 数组', async () => {
    await directUploadPartUrls('sess-1', [1, 2, 3], 3)
    expect(mockedApi.post).toHaveBeenCalledWith('/teams/3/documents/direct-uploads/sess-1/part-urls', {
      partNumbers: [1, 2, 3],
    })
  })

  it('status/commit/abort：sessionId 端点按 teamId 选前缀', async () => {
    await directUploadStatus('sess-1', null)
    expect(mockedApi.get).toHaveBeenCalledWith('/documents/direct-uploads/sess-1')
    await directUploadCommit('sess-1', { parts: [{ partNumber: 1, etag: 'e', size: 5 }] }, null)
    expect(mockedApi.post).toHaveBeenCalledWith('/documents/direct-uploads/sess-1/commit', {
      parts: [{ partNumber: 1, etag: 'e', size: 5 }],
    })
    await directUploadAbort('sess-1', 3)
    expect(mockedApi.post).toHaveBeenCalledWith('/teams/3/documents/direct-uploads/sess-1/abort')
  })
})
