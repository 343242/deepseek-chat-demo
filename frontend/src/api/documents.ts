import { useQuery, useMutation, useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { api, apiFetch } from '@/lib/api-fetch'
import type { PagedResult } from '@/types/api'
import type {
  DocumentDTO,
  ChunkDTO,
  EtlStatus,
  ChunkUploadInitRequest,
  ChunkUploadResult,
} from '@/types/document'
import { UPLOAD_LIMITS } from '@/lib/constants'

export const docKeys = {
  /** 根键：失效时用作前缀匹配，覆盖所有 documents 子键 */
  all: ['documents'] as const,
  list: (teamId?: number | null) => ['documents', teamId ?? 'personal'] as const,
  detail: (id: number) => ['documents', id] as const,
  chunks: (id: number) => ['documents', id, 'chunks'] as const,
  history: (id: number) => ['documents', id, 'history'] as const,
}

interface ListParams {
  teamId?: number | null
  page?: number
  size?: number
  status?: EtlStatus | EtlStatus[]
}

/** GET /api/documents（?teamId= 团队模式） */
export function useDocuments(params: ListParams = { size: 20 }) {
  return useInfiniteQuery({
    queryKey: [...docKeys.list(params.teamId), params],
    queryFn: ({ pageParam }) =>
      api.get<PagedResult<DocumentDTO>>('/documents', {
        params: { teamId: params.teamId ?? undefined, page: pageParam ?? 1, size: params.size ?? 20 },
      }),
    initialPageParam: 1,
    getNextPageParam: (last) => (last.page < last.totalPages ? last.page + 1 : undefined),
  })
}

export function useDocumentDetail(id: number | null) {
  return useQuery({
    queryKey: docKeys.detail(id ?? 0),
    queryFn: () => api.get<DocumentDTO>(`/documents/${id}`),
    enabled: !!id,
  })
}

/** GET /api/documents/{id}/chunks（分页） */
export function useDocumentChunks(id: number | null, page = 1, size = 20) {
  return useQuery({
    queryKey: [...docKeys.chunks(id ?? 0), page, size],
    queryFn: () =>
      api.get<PagedResult<ChunkDTO>>(`/documents/${id}/chunks`, { params: { page, size } }),
    enabled: !!id,
  })
}

/** GET /api/documents/{id}/history —— 版本历史 */
export function useDocumentHistory(id: number | null) {
  return useQuery({
    queryKey: docKeys.history(id ?? 0),
    queryFn: () => api.get<DocumentDTO[]>(`/documents/${id}/history`),
    enabled: !!id,
  })
}

/** GET /api/chunks/{chunkId} —— 单片段全文（ReferenceCard 展开用） */
export function fetchChunk(chunkId: string) {
  return api.get<ChunkDTO>(`/chunks/${chunkId}`)
}

export function useDeleteDocument() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.post<void>(`/documents/${id}/delete`),
    onSuccess: () => qc.invalidateQueries({ queryKey: docKeys.all }),
  })
}

export function useRetryDocument() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.post<DocumentDTO>(`/documents/${id}/retry`),
    onSuccess: () => qc.invalidateQueries({ queryKey: docKeys.all }),
  })
}

/* ============ 上传 ============ */

/** 小文件直传（≤ chunkThreshold） */
export function uploadDirect(file: File, teamId?: number | null) {
  const fd = new FormData()
  fd.append('file', file)
  if (teamId) fd.append('teamId', String(teamId))
  return apiFetch<DocumentDTO>('/documents/upload', { method: 'POST', body: fd })
}

/** 分片上传 init */
export function chunkUploadInit(req: ChunkUploadInitRequest) {
  return api.post<ChunkUploadResult>('/documents/multipart', req)
}

/** 上传单分片（raw body + X-Chunk-Checksum header，SHA-256） */
export async function uploadChunk(
  uploadId: string,
  chunkIndex: number,
  data: Blob,
  checksum: string,
): Promise<void> {
  await apiFetch<void>(`/documents/multipart/${uploadId}/chunks/${chunkIndex}`, {
    method: 'POST',
    body: data,
    headers: { 'X-Chunk-Checksum': checksum, 'Content-Type': 'application/octet-stream' },
  })
}

/** 分片上传完成 → documentId */
export function chunkUploadComplete(uploadId: string) {
  return api.post<{ documentId: number }>(`/documents/multipart/${uploadId}/complete`)
}

/** 取消/清理分片上传 session */
export function chunkUploadDelete(uploadId: string) {
  return api.post<void>(`/documents/multipart/${uploadId}/delete`)
}

/** 上传策略：> chunkThreshold 走分片，否则直传 */
export function shouldChunk(fileSize: number): boolean {
  return fileSize > UPLOAD_LIMITS.chunkThreshold
}
