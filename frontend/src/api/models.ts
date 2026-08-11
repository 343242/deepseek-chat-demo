import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-fetch'
import type { ModelVO, ModelCapability } from '@/types/chat'

export const modelKeys = {
  detail: ['models', 'detail'] as const,
}

/** GET /api/models/detail —— 候选模型（含 provider/capability，按 provider 分组） */
export function useModels(capability: ModelCapability = 'CHAT') {
  return useQuery({
    queryKey: [...modelKeys.detail, capability],
    queryFn: () => api.get<ModelVO[]>('/models/detail', { params: { capability } }),
    staleTime: 5 * 60_000,
  })
}

/** POST /api/models/refresh —— 刷新模型列表（需 model:config 权限） */
export function useRefreshModels() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<void>('/models/refresh'),
    onSuccess: () => qc.invalidateQueries({ queryKey: modelKeys.detail }),
  })
}
