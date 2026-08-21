import { describe, it, expect, vi, afterEach, type Mock } from 'vitest'
import { renderHook, act, waitFor, cleanup } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { apiFetch } from '@/lib/api-fetch'
import { useResults, useStartRun, useDatasets } from '../evaluation'

vi.mock('@/lib/api-fetch', () => ({
  apiFetch: vi.fn(),
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() },
}))

const fetchMock = apiFetch as unknown as Mock

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}

// vitest 未开 globals，testing-library 自动 cleanup 不会注册，需手动卸载
afterEach(() => {
  fetchMock.mockReset()
  cleanup()
})

describe('useResults — EVAL-2 分页 0 基换算 + jsonb 解包', () => {
  it('UI 1 基 page 换算为 API 0 基，并归一化 snake_case 行', async () => {
    fetchMock.mockResolvedValueOnce({
      results: [
        {
          id: 1,
          run_id: 7,
          item_id: 3,
          item_question_snapshot: '什么是混合检索？',
          retrieval_metrics: '{"recall":0.5}',
          generation_metrics: { type: 'jsonb', value: '{"faithfulness":-1}' },
          error: null,
          latency_ms: 1200,
        },
      ],
      total: 1,
    })

    const { result } = renderHook(() => useResults(7, 1), { wrapper })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(fetchMock).toHaveBeenCalledWith('/evaluation/runs/7/results', {
      method: 'GET',
      raw: true,
      params: { page: 0, size: 50 },
      signal: expect.anything(),
    })
    expect(result.current.data?.items[0]).toEqual({
      id: 1,
      runId: 7,
      itemId: 3,
      question: '什么是混合检索？',
      retrieval: { recall: 0.5 },
      generation: { faithfulness: -1 },
      error: null,
      latencyMs: 1200,
    })
  })
})

describe('useStartRun — 启动请求体（202 由 raw 模式原样返回）', () => {
  it('以 {datasetId, name, configOverride} 调 POST /evaluation/runs', async () => {
    fetchMock.mockResolvedValueOnce({ runId: 9, status: 'running', message: '' })

    const { result } = renderHook(() => useStartRun(), { wrapper })
    let returned: { runId: number } | undefined
    await act(async () => {
      returned = await result.current.mutateAsync({
        datasetId: 3,
        name: 'run-a',
        configOverride: { topK: 5, rerankEnabled: false },
      })
    })

    expect(fetchMock).toHaveBeenCalledWith('/evaluation/runs', {
      method: 'POST',
      raw: true,
      json: { datasetId: 3, name: 'run-a', configOverride: { topK: 5, rerankEnabled: false } },
    })
    expect(returned?.runId).toBe(9)
  })
})

describe('useDatasets — 列表形状适配（非 PagedResult）', () => {
  it('{datasets,total,page,size} 适配为统一分页形状（page 保持 0 基）', async () => {
    fetchMock.mockResolvedValueOnce({
      datasets: [{ id: 1, version: 1, itemCount: 10 }],
      total: 30,
      page: 0,
      size: 20,
    })

    const { result } = renderHook(() => useDatasets(20), { wrapper })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(fetchMock).toHaveBeenCalledWith('/evaluation/datasets', {
      method: 'GET',
      raw: true,
      params: { page: 0, size: 20 },
      signal: expect.anything(),
    })
    expect(result.current.data?.pages[0]).toEqual({
      content: [{ id: 1, version: 1, itemCount: 10 }],
      page: 0,
      total: 30,
      totalPages: 2,
    })
  })
})
