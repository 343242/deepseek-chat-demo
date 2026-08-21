import { describe, it, expect } from 'vitest'
import {
  parseMetricField,
  formatMetric,
  summarizeConfig,
  buildCompareRows,
  formatDiff,
  parseJsonObject,
} from '../eval-metrics'

/* ============ parseMetricField —— jsonb 三态防御解包（design.md §4） ============ */

describe('parseMetricField — jsonb 三态解包', () => {
  it('已解对象：原样返回', () => {
    const obj = { recall: 0.5, precision: 1 }
    expect(parseMetricField(obj)).toBe(obj)
  })

  it('JSON 字符串：解析为对象', () => {
    expect(parseMetricField('{"recall":0.5}')).toEqual({ recall: 0.5 })
  })

  it('PGobject 形态 {type:"jsonb", value:"<json>"}：解包内层 JSON', () => {
    const pgObject = { type: 'jsonb', value: '{"faithfulness":0.8}' }
    expect(parseMetricField(pgObject)).toEqual({ faithfulness: 0.8 })
  })

  it('PGobject 但内层不是 JSON：返回 null 而非抛错', () => {
    expect(parseMetricField({ type: 'jsonb', value: 'not-json' })).toBeNull()
  })

  it('null/undefined：返回 null', () => {
    expect(parseMetricField(null)).toBeNull()
    expect(parseMetricField(undefined)).toBeNull()
  })

  it('非法 JSON 字符串：返回 null', () => {
    expect(parseMetricField('{broken')).toBeNull()
  })

  it('解析结果为标量（非对象）：返回 null', () => {
    expect(parseMetricField('"123"')).toBeNull()
    expect(parseMetricField('42')).toBeNull()
  })
})

/* ============ formatMetric —— -1 哨兵（线框 §4.1：不可当 0 参与展示） ============ */

describe('formatMetric — 哨兵与格式化', () => {
  it('-1 哨兵显示为 —', () => {
    expect(formatMetric(-1)).toBe('—')
  })

  it('null/undefined 显示为 —', () => {
    expect(formatMetric(null)).toBe('—')
    expect(formatMetric(undefined)).toBe('—')
  })

  it('正常值两位小数', () => {
    expect(formatMetric(0.7234)).toBe('0.72')
    expect(formatMetric(1)).toBe('1.00')
    expect(formatMetric(0)).toBe('0.00')
  })
})

/* ============ summarizeConfig —— configSnapshot 摘要（线框 §3.2） ============ */

describe('summarizeConfig — 配置摘要', () => {
  it('空/非法快照 → 默认配置', () => {
    expect(summarizeConfig(null)).toBe('默认配置')
    expect(summarizeConfig('')).toBe('默认配置')
    expect(summarizeConfig('{bad')).toBe('默认配置')
  })

  it('空对象（全默认） → 默认配置', () => {
    expect(summarizeConfig('{}')).toBe('默认配置')
  })

  it('topK + 显式关闭项按序拼接', () => {
    expect(summarizeConfig('{"topK":5,"rerankEnabled":false}')).toBe('topK=5 · 无重排')
    expect(summarizeConfig('{"topK":10,"mmrEnabled":false,"generationEnabled":false}')).toBe(
      'topK=10 · 无MMR · 未生成答案',
    )
  })

  it('开启项不列出（后端默认全开，只列偏差）', () => {
    expect(summarizeConfig('{"topK":10,"rerankEnabled":true}')).toBe('topK=10')
  })
})

/* ============ buildCompareRows —— 基线差值（design.md §5） ============ */

describe('buildCompareRows — 对比行构造', () => {
  const columns = [
    { runId: 1, name: 'baseline', entry: { runId: 1, summary: null, metrics: { avg_recall: 0.72, avg_mrr: 0.66, total_items: 100, error_items: 0 } } },
    { runId: 2, name: 'topk5', entry: { runId: 2, summary: null, metrics: { avg_recall: 0.81, total_items: 100, error_items: 2 } } },
    { runId: 3, name: 'no-rerank', entry: null },
  ]

  const rows = buildCompareRows(columns)

  it('基线列 diff 为 null，其余列计算差值', () => {
    const recall = rows.find((r) => r.def.key === 'avg_recall')
    expect(recall?.cells[0]).toEqual({ value: 0.72, diff: null })
    expect(recall?.cells[1]?.value).toBe(0.81)
    expect(recall?.cells[1]?.diff).toBeCloseTo(0.09, 10)
  })

  it('任一侧为 null 时 diff 为 null（该 run 未计算此指标）', () => {
    const mrr = rows.find((r) => r.def.key === 'avg_mrr')
    expect(mrr?.cells[1]?.value).toBeNull()
    expect(mrr?.cells[1]?.diff).toBeNull()
  })

  it('compare 条目丢失（重名被覆盖）→ 整列 null', () => {
    const recall = rows.find((r) => r.def.key === 'avg_recall')
    expect(recall?.cells[2]).toEqual({ value: null, diff: null })
  })

  it('覆盖检索侧 5 项 + 生成侧指标行', () => {
    const retrieval = rows.filter((r) => r.def.group === 'retrieval')
    expect(retrieval.length).toBe(5)
    expect(rows.length).toBeGreaterThan(5)
  })
})

/* ============ formatDiff / parseJsonObject ============ */

describe('formatDiff — 涨跌箭头', () => {
  it('正差 ▲、负差 ▼、零 ±', () => {
    expect(formatDiff(0.09)).toMatch(/^▲ /)
    expect(formatDiff(-0.14)).toMatch(/^▼ /)
    expect(formatDiff(0)).toBe('± 0.00')
  })

  it('null → 空串（基线列）', () => {
    expect(formatDiff(null)).toBe('')
  })
})

describe('parseJsonObject — JSON 字符串列解析', () => {
  it('summary 字符串解析（成功与失败路径）', () => {
    expect(parseJsonObject('{"totalItems":100,"error":"x"}')).toEqual({ totalItems: 100, error: 'x' })
    expect(parseJsonObject(null)).toBeNull()
    expect(parseJsonObject('oops')).toBeNull()
  })
})
