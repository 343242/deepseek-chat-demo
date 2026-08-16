/** RAG 文档相关契约 —— 来源 rag/dto/DocumentDTO.java · mode/Reference.java · ChunkController */

/** 文档处理状态 EtlStatus（11 值，DS §4.4.1） */
export type EtlStatus =
  | 'UPLOADED'
  | 'PENDING_APPROVAL'
  | 'PARSING'
  | 'CHUNKING'
  | 'VECTORIZING'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'FAILED'
  | 'VECTOR_FAILED'
  | 'REJECTED'
  | 'SUPERSEDED'

/** DocumentDTO（rag/dto/DocumentDTO.java） */
export interface DocumentDTO {
  id: number
  fileName: string
  fileSize: number
  mimeType: string
  chunkCount?: number | null
  status: EtlStatus
  errorMessage?: string | null
  userId: number
  teamId?: number | null
  version?: number | null
  supersededBy?: number | null
  documentGroupId?: string | null
  createTime: string
  /** 是否可在线预览（PDF 恒真；TXT/MD/HTML ≤ 预览上限；OOXML 恒假。后端 DocumentPreviewPolicy 按规范 MIME + 大小计算） */
  previewable: boolean
}

/** DocumentUploadResponse（rag/dto/DocumentUploadResponse.java，单传 / 批量共用；部分失败时 id=null + FAILED） */
export interface DocumentUploadResponse {
  id: number | null
  fileName: string
  status: EtlStatus
}

/** DocumentDeleteResult（rag/dto/DocumentDeleteResult.java，批量删除部分成功语义；失败项 message 携带原因） */
export interface DocumentDeleteResult {
  id: number
  success: boolean
  message?: string | null
}

/** ChunkDTO（GET /api/documents/{id}/chunks 与 GET /api/chunks/{chunkId}） */
export interface ChunkDTO {
  id: string
  content: string
  documentId: string
  fileName: string
  metadata?: Record<string, unknown>
}

/** RAG 引用来源（mode/Reference.java；agent + chat 双路径统一） */
export interface Reference {
  refNumber: number
  chunkId: string
  documentId: string
  fileName: string
  page?: number | null
  /** 相关性得分（可空，agent 路径常见）。归一化前原值，跨路径不可比（DS §11.8） */
  score?: number | null
  /** 来源 Tool 名（hybridSearch/vectorSearch…，可空） */
  source?: string | null
  /** 截断的 chunk 内容预览（可空） */
  content?: string | null
}

/* ============ 分片上传 ChunkUploadController ============ */

/** 分片上传初始化请求。请求 DTO 字段 readonly（FE-021 决策：防构造后意外变异） */
export interface ChunkUploadInitRequest {
  /** 整文件校验和（SHA-256 小写 hex；前端先行，后端同步迁移，算法见 CHECKSUM_ALGORITHM） */
  readonly fileChecksum: string
  readonly fileName: string
  readonly fileSize: number
  readonly mimeType: string
  readonly chunkSize: number
  /** 增量更新：替代的旧文档 id */
  readonly replaceDocumentId?: number | null
  readonly teamId?: number | null
}

/** 分片初始化结果 */
export interface ChunkUploadResult {
  uploaded: boolean
  uploadId?: string
  chunkSize?: number
  totalChunks?: number
  uploadedChunks?: number[]
  documentId?: number
}
