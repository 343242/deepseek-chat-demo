import { describe, it, expect, vi, afterEach, type Mock } from 'vitest'
import { renderHook, act, cleanup } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { api } from '@/lib/api-fetch'
import { useDeleteDocumentsBatch } from '../documents'
import type { DocumentDeleteResult } from '@/types/document'

vi.mock('@/lib/api-fetch', () => ({
  apiFetch: vi.fn(),
  api: { get: vi.fn(), post: vi.fn().mockResolvedValue([]), put: vi.fn(), del: vi.fn() },
}))

const postMock = api.post as unknown as Mock

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}

// vitest 未开 globals，testing-library 自动 cleanup 不会注册，需手动卸载
afterEach(() => {
  postMock.mockReset()
  cleanup()
})

describe('useDeleteDocumentsBatch — 批量删除请求构造', () => {
  it('以 { ids } JSON body 调 POST /documents/batch-delete，透传部分成功结果', async () => {
    const results: DocumentDeleteResult[] = [
      { id: 1, success: true },
      { id: 2, success: false, message: '文档不存在: 2' },
    ]
    postMock.mockResolvedValueOnce(results)

    const { result } = renderHook(() => useDeleteDocumentsBatch(), { wrapper })
    let returned: DocumentDeleteResult[] | undefined
    await act(async () => {
      returned = await result.current.mutateAsync([1, 2])
    })

    expect(postMock).toHaveBeenCalledWith('/documents/batch-delete', { ids: [1, 2] })
    expect(returned).toEqual(results)
  })
})
