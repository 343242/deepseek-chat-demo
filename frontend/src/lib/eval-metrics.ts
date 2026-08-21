/**
 * 评估模块纯函数 —— 指标解包/格式化、配置摘要、对比表数据构造（线框 09 §3–§4）
 *
 * 设计依据：wireframes/09-evaluation.md §4.1（-1 哨兵显示"—"，不可当 0 参与均值）、
 * design.md §4（jsonb 三态防御解包）、§5（compare 按 runId 反查）。
 */

import type {
  EvalAggregateMetrics,
  EvalCompareResponse,
  EvalConfigSnapshot,
  EvalRunSummary,
} from '@/types/evaluation'

/** 生成指标"未计算"哨兵（GenerationMetrics.java 约定） */
export const METRIC_SENTINEL = -1

/** JSON 文本 → 对象（解析失败/非对象返回 null；评估模块 summary/configSnapshot 均为 JSON 字符串列） */
export function parseJsonObject<T>(text: string | null | undefined): T | null {
  if (!text) return null
  try {
    const parsed: unknown = JSON.parse(text)
    return typeof parsed === 'object' && parsed !== null ? (parsed as T) : null
  } catch {
    return null
  }
}

/**
 * jsonb 指标字段三态解包 → 对象：
 * ① 已解对象（Jackson 直序列化）② JSON 字符串 ③ PGobject 形态 {type:'jsonb', value:'<json>'}
 * （JdbcTemplate.queryForList 读 jsonb 无类型处理器时 pg 驱动返回 PGobject，design.md §4）
 */
export function parseMetricField<T>(field: unknown): T | null {
  if (field == null) return null
  if (typeof field === 'object') {
    const obj = field as Record<string, unknown>
    if (typeof obj.value === 'string' && (obj.type === 'jsonb' || obj.type === 'json')) {
      return parseJsonObject<T>(obj.value)
    }
    return field as T
  }
  if (typeof field === 'string') return parseJsonObject<T>(field)
  return null
}

/** 指标格式化：null/undefined/-1 哨兵 → '—'；否则两位小数 */
export function formatMetric(value: number | null | undefined): string {
  if (value == null || value === METRIC_SENTINEL) return '—'
  return value.toFixed(2)
}

/** run.summary 解析（失败路径 summary 为 {"error": "..."}，成功路径为计数对象） */
export function parseRunSummary(summary: string | null | undefined): EvalRunSummary | null {
  return parseJsonObject<EvalRunSummary>(summary)
}

/**
 * configSnapshot JSON → 摘要标签："topK=10 · 无重排 · 无MMR"。
 * 后端默认全开，故只列显式关闭项；空快照 → '默认配置'（线框 §3.2 配置摘要列）。
 */
export function summarizeConfig(snapshot: string | null | undefined): string {
  const config = parseJsonObject<EvalConfigSnapshot>(snapshot)
  if (!config) return '默认配置'
  const parts: string[] = []
  if (config.topK != null) parts.push(`topK=${config.topK}`)
  if (config.rerankEnabled === false) parts.push('无重排')
  if (config.mmrEnabled === false) parts.push('无MMR')
  if (config.parentChildEnabled === false) parts.push('无父子分块')
  if (config.queryRewriteEnabled === false) parts.push('无查询改写')
  if (config.generationEnabled === false) parts.push('未生成答案')
  if (config.testUserId != null) parts.push(`uid=${config.testUserId}`)
  return parts.length > 0 ? parts.join(' · ') : '默认配置'
}

/* ============ 多 Run 对比（线框 §4.2） ============ */

export interface CompareMetricDef {
  /** EvalAggregateMetrics 键（avg_* 蛇形，对齐后端聚合 SQL） */
  key: keyof EvalAggregateMetrics
  label: string
  group: 'retrieval' | 'generation'
}

/** 对比表指标行（检索侧 5 + 生成侧 9，覆盖全部聚合指标） */
export const COMPARE_METRIC_DEFS: readonly CompareMetricDef[] = [
  { key: 'avg_recall', label: 'Recall@K', group: 'retrieval' },
  { key: 'avg_precision', label: 'Precision@K', group: 'retrieval' },
  { key: 'avg_mrr', label: 'MRR', group: 'retrieval' },
  { key: 'avg_ndcg', label: 'NDCG', group: 'retrieval' },
  { key: 'avg_context_precision', label: '上下文精度', group: 'retrieval' },
  { key: 'avg_faithfulness', label: 'Faithfulness', group: 'generation' },
  { key: 'avg_answer_relevance', label: '答案相关性', group: 'generation' },
  { key: 'avg_context_relevance', label: '上下文相关性', group: 'generation' },
  { key: 'avg_answer_correctness', label: '答案正确性', group: 'generation' },
  { key: 'avg_noise_sensitivity', label: '噪声敏感度', group: 'generation' },
  { key: 'avg_factual_correctness', label: '事实正确性', group: 'generation' },
  { key: 'avg_rouge_l', label: 'Rouge-L', group: 'generation' },
  { key: 'avg_bleu', label: 'BLEU', group: 'generation' },
  { key: 'avg_answer_similarity', label: '答案相似度', group: 'generation' },
] as const

/** 对比单元格：基线列 diff=null；null=该 run 未计算此指标 */
export interface CompareCell {
  value: number | null
  /** 相对基线差值（基线自身或任一侧为 null 时为 null） */
  diff: number | null
}

export interface CompareRow {
  def: CompareMetricDef
  cells: CompareCell[]
}

/** 对比列（以选中顺序的 runId 为准，从 comparison 按 runId 反查条目；design.md §5） */
export interface CompareColumn {
  runId: number
  name: string
  entry: EvalCompareResponse['comparison'][string] | null
}

/**
 * 构造对比表行：首列为基线，其余列计算 ▲▼ 差值。
 * comparison 按 run name 键控（重名互相覆盖），此处按 runId 反查，查不到的列整列 '—'。
 */
export function buildCompareRows(columns: readonly CompareColumn[]): CompareRow[] {
  return COMPARE_METRIC_DEFS.map((def) => {
    const baselineRaw = columns[0]?.entry?.metrics?.[def.key]
    const baseline = typeof baselineRaw === 'number' ? baselineRaw : null
    const cells: CompareCell[] = columns.map((col, i) => {
      const raw = col.entry?.metrics?.[def.key]
      const value = typeof raw === 'number' ? raw : null
      const diff = i === 0 || value == null || baseline == null ? null : value - baseline
      return { value, diff }
    })
    return { def, cells }
  })
}

/** 差值格式化：null → ''；0 → '±0.00'；正 ▲ / 负 ▼ 由调用方着色（DS 12.6：箭头+数字，不以色彩为唯一载体） */
export function formatDiff(diff: number | null): string {
  if (diff == null) return ''
  const abs = Math.abs(diff).toFixed(2)
  if (diff > 0) return `▲ ${abs}`
  if (diff < 0) return `▼ ${abs}`
  return `± 0.00`
}
