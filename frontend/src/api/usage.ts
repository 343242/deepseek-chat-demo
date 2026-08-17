import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api-fetch'
import type { PagedResult } from '@/types/api'

/** 调用场景（UsageScene 枚举，后端白名单绑定） */
export type UsageScene = 'CHAT' | 'AGENT' | 'INTENT'

/** GET /api/usage/records 明细行（UsageEventDTO） */
export interface UsageEventDTO {
  eventId: string
  userId: number
  scene: UsageScene
  conversationId?: string | null
  modelId: string
  promptTokens?: number | null
  completionTokens?: number | null
  totalTokens?: number | null
  estimated: boolean
  success: boolean
  durationMs: number
  createdAt?: string | null
}

/** GET /api/usage/summary 总计（UsageSummaryDTO） */
export interface UsageSummaryDTO {
  requestCount: number
  successCount: number
  successRate: number
  totalPromptTokens: number
  totalCompletionTokens: number
  totalTokens: number
  avgDurationMs: number
  maxDurationMs: number
}

/** GET /api/usage/timeline 时间桶点（UsageTimelinePointDTO，空桶补零） */
export interface UsageTimelinePointDTO {
  bucket: string
  requestCount: number
  totalPromptTokens: number
  totalCompletionTokens: number
  totalTokens: number
}

/** GET /api/usage/stats 分组聚合行（UsageStatsDTO） */
export interface UsageStatsDTO {
  groupKey: string
  requestCount: number
  successRate: number
  totalPromptTokens: number
  totalCompletionTokens: number
  totalTokens: number
  avgDurationMs: number
}

export type UsageStatsDim = 'MODEL' | 'SCENE' | 'USER'
export type UsageStatsSort = 'TOTAL_TOKENS' | 'REQUEST_COUNT' | 'AVG_DURATION_MS'
export type UsageStatsOrder = 'ASC' | 'DESC'
export type TimelineGranularity = 'DAY' | 'MONTH'

/** 查询过滤（本人维度下 userId 由后端取当前登录用户） */
export interface UsageQueryParams {
  userId?: number
  scene?: UsageScene
  model?: string
  conversation?: string
  start?: string
  end?: string
}

export const usageKeys = {
  all: ['usage'] as const,
  summary: (params: UsageQueryParams) => [...usageKeys.all, 'summary', params] as const,
  timeline: (granularity: TimelineGranularity, params: UsageQueryParams) =>
    [...usageKeys.all, 'timeline', granularity, params] as const,
  stats: (
    dim: UsageStatsDim,
    sort: UsageStatsSort,
    order: UsageStatsOrder,
    params: UsageQueryParams,
  ) => [...usageKeys.all, 'stats', dim, sort, order, params] as const,
  records: (params: UsageQueryParams, page: number, size: number) =>
    [...usageKeys.all, 'records', params, page, size] as const,
}

/** 展开为 apiFetch 可序列化的 query 参数（过滤 undefined） */
function toParams(params: UsageQueryParams): Record<string, string | number | undefined> {
  return {
    userId: params.userId,
    scene: params.scene,
    model: params.model,
    conversation: params.conversation,
    start: params.start,
    end: params.end,
  }
}

export function useUsageSummary(params: UsageQueryParams) {
  return useQuery({
    queryKey: usageKeys.summary(params),
    queryFn: () => api.get<UsageSummaryDTO>('/usage/summary', { params: toParams(params) }),
  })
}

export function useUsageTimeline(granularity: TimelineGranularity, params: UsageQueryParams) {
  return useQuery({
    queryKey: usageKeys.timeline(granularity, params),
    queryFn: () =>
      api.get<UsageTimelinePointDTO[]>('/usage/timeline', { params: { granularity, ...toParams(params) } }),
  })
}

export function useUsageStats(
  dim: UsageStatsDim,
  sort: UsageStatsSort,
  order: UsageStatsOrder,
  params: UsageQueryParams,
) {
  return useQuery({
    queryKey: usageKeys.stats(dim, sort, order, params),
    queryFn: () =>
      api.get<UsageStatsDTO[]>('/usage/stats', { params: { dim, sort, order, ...toParams(params) } }),
  })
}

export function useUsageRecords(params: UsageQueryParams, page: number, size = 20) {
  return useQuery({
    queryKey: usageKeys.records(params, page, size),
    queryFn: () =>
      api.get<PagedResult<UsageEventDTO>>('/usage/records', {
        params: { ...toParams(params), page, size },
      }),
  })
}
