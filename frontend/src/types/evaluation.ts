/** 评估模块契约 —— 来源 evaluation/dataset/* · testset/GenerationJobRecord.java · runner/EvaluationRun.java · DatasetController/EvaluationRunController
 *  ⚠️ 三处契约差异（EVAL-2）：响应为裸 JSON（无 GlobalResponse 信封）、分页 page 从 0 起、启动类端点返回 202。
 *  时间均为 "yyyy-MM-dd HH:mm:ss" 字符串（JacksonTimeConfig，上海时区，无偏移量）。
 */

/** 条目状态 EvaluationItemStatus（@JsonValue 小写；未知 DB 值后端降级为 draft） */
export type EvalItemStatus = 'draft' | 'approved' | 'rejected'

/** 运行状态 EvaluationRunStatus（@JsonValue 小写；未知值后端降级为 pending） */
export type EvalRunStatus = 'pending' | 'running' | 'completed' | 'failed'

/** 生成任务状态（evaluation_dataset_gen_run.status 字符串列） */
export type EvalGenJobStatus = 'pending' | 'running' | 'completed' | 'failed'

/** EvaluationDataset（dataset/EvaluationDataset.java；列表端点 items=null，仅详情端点含 items） */
export interface EvalDataset {
  id?: number | null
  name?: string | null
  description?: string | null
  version: number
  source?: string | null
  judgeModel?: string | null
  itemCount: number
  createdAt?: string | null
  updatedAt?: string | null
  items?: EvalDatasetItem[] | null
}

/** EvaluationDatasetItem（dataset/EvaluationDatasetItem.java，按 seq 排序） */
export interface EvalDatasetItem {
  id?: number | null
  datasetId?: number | null
  question?: string | null
  groundTruthAnswer?: string | null
  relevantChunkIds?: string[] | null
  relevantContent?: string | null
  tags?: string[] | null
  status?: EvalItemStatus | null
  seq: number
}

/** 条目部分更新请求（POST /datasets/{datasetId}/items/{itemId}；null 字段后端保留旧值——未改动字段不提交） */
export interface EvalItemUpdateRequest {
  readonly question?: string | null
  readonly groundTruthAnswer?: string | null
  readonly relevantChunkIds?: string[] | null
  readonly relevantContent?: string | null
  readonly tags?: string[] | null
  readonly status?: EvalItemStatus | null
}

/** GenerationJobRecord（testset/GenerationJobRecord.java） */
export interface EvalGenerationJob {
  id: number
  name: string
  userId: number
  status: EvalGenJobStatus
  configJson?: string | null
  /** 最近一次 GenerationProgressEvent 的序列化：{"phase","current","total","message"} */
  progressJson?: string | null
  /** 完成后写入的数据集 id */
  datasetId?: number | null
  error?: string | null
  startedAt?: string | null
  completedAt?: string | null
  createdAt?: string | null
}

/** 生成进度（progressJson 解析形态） */
export interface EvalGenerationProgress {
  phase: 'sampling' | 'kg_build' | 'edges' | 'scenarios' | 'synthesis' | 'done'
  current: number
  total: number
  message: string
}

/** EvaluationRun（runner/EvaluationRun.java；summary 与 configSnapshot 为 JSON 字符串，需前端解析） */
export interface EvalRun {
  id?: number | null
  datasetId?: number | null
  name?: string | null
  configSnapshot?: string | null
  status?: EvalRunStatus | null
  generationModel?: string | null
  judgeModel?: string | null
  summary?: string | null
  startedAt?: string | null
  completedAt?: string | null
  createdAt?: string | null
}

/** run summary（EvaluationExecutionService 完成时写入；失败路径为 {"error": "..."}） */
export interface EvalRunSummary {
  totalItems?: number
  successCount?: number
  failCount?: number
  avgLatencyMs?: number
  error?: string
}

/** 启动评测请求的 configOverride（StartRunRequest；键为 EvaluationRunner.EvalConfig 接受的键，全部可省略走后端默认） */
export interface EvalConfigOverride {
  /** 同时覆盖 vector/bm25/rrf 三路 topK（1-50） */
  readonly topK?: number
  readonly rerankEnabled?: boolean
  readonly mmrEnabled?: boolean
  readonly parentChildEnabled?: boolean
  readonly queryRewriteEnabled?: boolean
  readonly generationEnabled?: boolean
  readonly testUserId?: number
}

/** configSnapshot 解析形态（即当时的 configOverride 原样快照） */
export type EvalConfigSnapshot = Partial<{
  topK: number
  rerankEnabled: boolean
  mmrEnabled: boolean
  parentChildEnabled: boolean
  queryRewriteEnabled: boolean
  generationEnabled: boolean
  testUserId: number
}>

/** 检索指标（retrieval_metrics jsonb，RetrievalMetrics.java） */
export interface RetrievalMetrics {
  recall: number
  precision: number
  mrr: number
  ndcg: number
  contextPrecision: number
}

/** 生成指标（generation_metrics jsonb，GenerationMetrics.java；-1 为"未计算"哨兵，展示为"—"） */
export interface GenerationMetrics {
  faithfulness: number
  contextRecall: number
  answerRelevance: number
  contextRelevance: number
  answerCorrectness: number
  noiseSensitivity: number
  contextPrecisionLlm: number
  factualCorrectness: number
  rougeL: number
  bleu: number
  answerSimilarity: number
  contextEntityRecall: number
  contextUtilization: number
}

/** 归一化结果行（api 层产出：snake_case 原始行 + jsonb 三态解包 → camelCase） */
export interface EvalResultItem {
  id: number
  runId: number
  itemId: number
  question: string | null
  retrieval: RetrievalMetrics | null
  generation: GenerationMetrics | null
  error: string | null
  latencyMs: number | null
}

/** compare 聚合指标（EvaluationResultRepository.aggregateMetricsByRunId：avg_* 蛇形键；null=该指标无样本，-1 哨兵已在 SQL 过滤） */
export interface EvalAggregateMetrics {
  avg_recall?: number | null
  avg_precision?: number | null
  avg_mrr?: number | null
  avg_ndcg?: number | null
  avg_context_precision?: number | null
  avg_faithfulness?: number | null
  avg_context_recall?: number | null
  avg_answer_relevance?: number | null
  avg_context_relevance?: number | null
  avg_answer_correctness?: number | null
  avg_noise_sensitivity?: number | null
  avg_context_precision_llm?: number | null
  avg_factual_correctness?: number | null
  avg_rouge_l?: number | null
  avg_bleu?: number | null
  avg_answer_similarity?: number | null
  avg_context_entity_recall?: number | null
  avg_context_utilization?: number | null
  total_items: number
  error_items: number
}

/** compare 单 run 条目（summary 已由后端解析为对象，解析失败降级为原始字符串） */
export interface EvalCompareEntry {
  runId: number
  summary: EvalRunSummary | string | null
  metrics: EvalAggregateMetrics
}

/** POST /runs/compare 响应（⚠️ 以 run name 为键，重名互相覆盖——前端以 runId 反查，design.md §5） */
export interface EvalCompareResponse {
  comparison: Record<string, EvalCompareEntry>
}

/** SSE event:progress 载荷（runner/EvaluationProgressEvent.java） */
export interface EvalRunProgressEvent {
  runId: number
  processed: number
  total: number
  successCount: number
  failCount: number
  itemId: number
  status: 'success' | 'failed'
  error?: string | null
  elapsedMs: number
}

/** SSE 终态帧载荷（done / error；bridgeTerminated 回放与流式收尾共用形态） */
export interface EvalRunTerminalEvent {
  runId?: number
  /** 'completed' / 'failed'（终态回放）；流式 error 帧可能缺失 */
  status?: string
  message?: string
}
