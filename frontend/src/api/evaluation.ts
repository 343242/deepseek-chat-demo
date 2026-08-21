/**
 * 评估模块 API 层 —— DatasetController(/api/evaluation/datasets) + EvaluationRunController(/api/evaluation/runs)
 *
 * 契约差异（EVAL-2，design.md §1/§3）：
 * - 全部走 apiFetch raw 模式（裸 JSON，无 GlobalResponse 信封；异常路径的 200 错误信封由 raw 分支兼容）
 * - 分页 0 基：本层 queryFn 做 UI 1 基 ↔ API 0 基换算（page - 1），组件无感知
 * - 启动类端点返回 202（res.ok 范围，raw 模式原样返回）
 */

import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api-fetch'
import { parseMetricField } from '@/lib/eval-metrics'
import { EVALUATION } from '@/lib/constants'
import type {
  EvalCompareResponse,
  EvalConfigOverride,
  EvalDataset,
  EvalGenerationJob,
  EvalItemUpdateRequest,
  EvalResultItem,
  EvalRun,
  GenerationMetrics,
  RetrievalMetrics,
} from '@/types/evaluation'

/* ============ raw 便捷方法（评估模块专用，见文件头注释） ============ */

function rawGet<T>(path: string, params?: Record<string, string | number | undefined>, signal?: AbortSignal) {
  return apiFetch<T>(path, { method: 'GET', raw: true, params, signal })
}

function rawPost<T>(path: string, json?: unknown) {
  return apiFetch<T>(path, { method: 'POST', raw: true, json })
}

/* ============ queryKey 工厂 ============ */

export const evalKeys = {
  all: ['evaluation'] as const,
  datasets: () => [...evalKeys.all, 'datasets'] as const,
  dataset: (id: number) => [...evalKeys.datasets(), id] as const,
  genJob: (jobId: number) => [...evalKeys.all, 'gen-job', jobId] as const,
  runs: (datasetId: number) => [...evalKeys.all, 'runs', datasetId] as const,
  results: (runId: number, page: number, size: number) =>
    [...evalKeys.all, 'results', runId, page, size] as const,
}

/* ============ 数据集 ============ */

/** 列表端点响应（手写 Map，非 PagedResult） */
interface DatasetListResponse {
  datasets: EvalDataset[]
  total: number
  page: number
  size: number
}

/** 列表适配为统一分页形状（page 保持后端 0 基） */
interface DatasetPage {
  content: EvalDataset[]
  page: number
  total: number
  totalPages: number
}

function mapDatasetPage(res: DatasetListResponse): DatasetPage {
  return {
    content: res.datasets,
    page: res.page,
    total: res.total,
    totalPages: Math.max(1, Math.ceil(res.total / Math.max(1, res.size))),
  }
}

/** 数据集列表（左栏；0 基无限分页——EVAL-2 契约，故 initialPageParam=0） */
export function useDatasets(size = 20) {
  return useInfiniteQuery({
    queryKey: [...evalKeys.datasets(), size],
    queryFn: ({ pageParam, signal }) =>
      rawGet<DatasetListResponse>('/evaluation/datasets', { page: pageParam, size }, signal).then(mapDatasetPage),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.page + 1 < last.totalPages ? last.page + 1 : undefined),
  })
}

/** 数据集详情（含全量 items，按 seq 排序；条目表为前端分页） */
export function useDataset(id: number | null) {
  return useQuery({
    queryKey: evalKeys.dataset(id ?? 0),
    queryFn: ({ signal }) => rawGet<EvalDataset>(`/evaluation/datasets/${id}`, undefined, signal),
    enabled: id != null,
  })
}

/** 提交生成任务 → 202 {jobId, status} */
export function useGenerateDataset() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: { name?: string; userId: number }) =>
      rawPost<{ jobId: number; status: string }>('/evaluation/datasets/generate', req),
    onSuccess: () => {
      // 任务完成事件由 useGenerationJob 轮询到终态后失效列表（此处只清旧列表缓存）
      void qc.invalidateQueries({ queryKey: evalKeys.datasets() })
    },
  })
}

/** 生成任务状态（design.md §2：轮询而非 SSE） */
export function useGenerationJob(jobId: number | null) {
  return useQuery({
    queryKey: evalKeys.genJob(jobId ?? 0),
    queryFn: ({ signal }) => rawGet<EvalGenerationJob>(`/evaluation/datasets/generate/${jobId}`, undefined, signal),
    enabled: jobId != null,
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status === 'pending' || status === 'running' ? EVALUATION.generationPollMs : false
    },
  })
}

/** 条目审核编辑（部分更新语义：未改动字段不提交 → 后端 null 保留旧值） */
export function useUpdateItem(datasetId: number) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ itemId, update }: { itemId: number; update: EvalItemUpdateRequest }) =>
      rawPost<{ status: string }>(`/evaluation/datasets/${datasetId}/items/${itemId}`, update),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: evalKeys.dataset(datasetId) })
    },
  })
}

/** 导出数据集 JSON（后端无 Content-Disposition，走 blob + anchor 下载） */
export async function exportDataset(id: number, fileName: string): Promise<void> {
  const parsed = await rawGet<unknown>(`/evaluation/datasets/${id}/export`)
  const blob = new Blob([JSON.stringify(parsed, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${fileName || `dataset-${id}`}.json`
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/* ============ 评测运行 ============ */

/** 启动评测 → 202 {runId, status} */
export function useStartRun() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: { datasetId: number; name?: string; configOverride?: EvalConfigOverride }) =>
      rawPost<{ runId: number; status: string; message: string }>('/evaluation/runs', req),
    onSuccess: (_data, variables) => {
      void qc.invalidateQueries({ queryKey: evalKeys.runs(variables.datasetId) })
    },
  })
}

/** 某数据集的全部运行（createdAt 倒序；存在 running 时轮询兜底，SSE done 亦会失效） */
export function useRuns(datasetId: number | null) {
  return useQuery({
    queryKey: evalKeys.runs(datasetId ?? 0),
    queryFn: ({ signal }) =>
      rawGet<{ runs: EvalRun[] }>(`/evaluation/runs/dataset/${datasetId}`, undefined, signal),
    enabled: datasetId != null,
    refetchInterval: (query) => {
      const hasRunning = query.state.data?.runs.some((r) => r.status === 'running' || r.status === 'pending')
      return hasRunning ? EVALUATION.runPollMs : false
    },
  })
}

/** 结果行原始形态（JdbcTemplate 行，snake_case 8 键；jsonb 字段三态见 parseMetricField） */
interface RawResultRow {
  id: number
  run_id: number
  item_id: number
  item_question_snapshot?: string | null
  retrieval_metrics?: unknown
  generation_metrics?: unknown
  error?: string | null
  latency_ms?: number | null
}

function normalizeResultRow(row: RawResultRow): EvalResultItem {
  return {
    id: row.id,
    runId: row.run_id,
    itemId: row.item_id,
    question: row.item_question_snapshot ?? null,
    retrieval: parseMetricField<RetrievalMetrics>(row.retrieval_metrics),
    generation: parseMetricField<GenerationMetrics>(row.generation_metrics),
    error: row.error ?? null,
    latencyMs: row.latency_ms ?? null,
  }
}

/** 单 run 结果（page 为 UI 1 基，本层换算 0 基） */
export function useResults(runId: number | null, page = 1, size = EVALUATION.resultsPageSize) {
  return useQuery({
    queryKey: evalKeys.results(runId ?? 0, page, size),
    queryFn: ({ signal }) =>
      rawGet<{ results: RawResultRow[]; total: number }>(
        `/evaluation/runs/${runId}/results`,
        { page: page - 1, size },
        signal,
      ).then((res) => ({
        items: res.results.map(normalizeResultRow),
        total: res.total,
      })),
    enabled: runId != null,
  })
}

/** 多 run 对比（≥2、≤10，后端校验；响应按 run name 键控，消费方按 runId 反查） */
export function useCompareRuns() {
  return useMutation({
    mutationFn: (runIds: number[]) => rawPost<EvalCompareResponse>('/evaluation/runs/compare', { runIds }),
  })
}
