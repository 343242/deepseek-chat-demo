import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api-fetch'
import type { Team } from '@/types/team'

export const teamKeys = {
  list: ['teams'] as const,
}

/** GET /api/teams —— 当前用户所属团队（知识库团队切换器 / 团队列表） */
export function useTeams() {
  return useQuery({
    queryKey: teamKeys.list,
    queryFn: () => api.get<Team[]>('/teams'),
    staleTime: 60_000,
  })
}
