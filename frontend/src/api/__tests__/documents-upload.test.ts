import { describe, it, expect, vi, beforeEach } from 'vitest'
import { apiFetch } from '@/lib/api-fetch'
import { uploadDirect, uploadBatch } from '../documents'
import type { DocumentUploadResponse } from '@/types/document'

vi.mock('@/lib/api-fetch', () => ({
  apiFetch: vi.fn().mockResolvedValue([]),
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() },
}))

const mockedFetch = vi.mocked(apiFetch)

function file(name: string): File {
  return new File(['hello'], name, { type: 'text/plain' })
}

beforeEach(() => {
  mockedFetch.mockReset()
})

describe('uploadBatch — 批量直传 FormData 构造', () => {
  it('多个文件追加为多个 files entry，携带 teamId', async () => {
    const results: DocumentUploadResponse[] = [
      { id: 1, fileName: 'a.md', status: 'UPLOADED' },
      { id: null, fileName: 'b.md', status: 'FAILED' },
    ]
    mockedFetch.mockResolvedValueOnce(results)

    await uploadBatch([file('a.md'), file('b.md')], 5)

    expect(mockedFetch).toHaveBeenCalledTimes(1)
    const [url, init] = mockedFetch.mock.calls[0]!
    expect(url).toBe('/documents/upload/batch')
    expect(init?.method).toBe('POST')
    const body = init?.body as FormData
    expect(body.getAll('files')).toHaveLength(2)
    expect(body.get('files')).toBeInstanceOf(File)
    expect(body.get('teamId')).toBe('5')
    await expect(mockedFetch.mock.results[0]!.value).resolves.toEqual(results)
  })

  it('无 teamId 时不追加该字段', async () => {
    await uploadBatch([file('a.md')])
    const body = vi.mocked(apiFetch).mock.calls[0]![1]?.body as FormData
    expect(body.has('teamId')).toBe(false)
  })
})

describe('uploadDirect — 单传 FormData 构造（replaceDocumentId 回归）', () => {
  it('携带 replaceDocumentId（"上传新版本"小文件场景，此前缺失导致新版本被存成独立文档）', async () => {
    await uploadDirect(file('a.pdf'), null, 42)

    const [url, init] = mockedFetch.mock.calls[0]!
    expect(url).toBe('/documents/upload')
    const body = init?.body as FormData
    expect(body.getAll('file')).toHaveLength(1)
    expect(body.get('replaceDocumentId')).toBe('42')
  })

  it('无 teamId / replaceDocumentId 时只含文件本体', async () => {
    await uploadDirect(file('a.pdf'))
    const body = mockedFetch.mock.calls[0]![1]?.body as FormData
    expect([...body.keys()]).toEqual(['file'])
  })
})
