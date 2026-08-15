import { describe, it, expect } from 'vitest'
import { mapFrame, parseEventBlock, parseJson } from '../sse'

describe('mapFrame', () => {
  describe('内容帧（无 event 名）', () => {
    it('null event → content', () => {
      expect(mapFrame(null, 'hello')).toEqual({ type: 'content', chunk: 'hello' })
    })
    it("'message' event → content", () => {
      expect(mapFrame('message', 'x')).toEqual({ type: 'content', chunk: 'x' })
    })
    it("空串 event → content", () => {
      expect(mapFrame('', 'x')).toEqual({ type: 'content', chunk: 'x' })
    })
  })

  it('reasoning 帧', () => {
    expect(mapFrame('reasoning', '思考')).toEqual({ type: 'reasoning', chunk: '思考' })
  })

  describe('references 帧', () => {
    it('合法 JSON → 解析', () => {
      const refs = [{ refNumber: 1, chunkId: 'c1', documentId: 'd1', fileName: 'a.pdf' }]
      expect(mapFrame('references', JSON.stringify(refs))).toEqual({
        type: 'references',
        references: refs,
      })
    })
    it('非法 JSON → 空数组兜底', () => {
      expect(mapFrame('references', 'not-json')).toEqual({ type: 'references', references: [] })
    })
  })

  describe('agentMetadata 帧', () => {
    it('合法 JSON → 解析', () => {
      const meta = { intent: 'SEARCH', confidence: 0.9 }
      expect(mapFrame('agentMetadata', JSON.stringify(meta))).toEqual({
        type: 'agentMetadata',
        metadata: meta,
      })
    })
    it('非法 JSON → 空对象兜底', () => {
      expect(mapFrame('agentMetadata', 'bad')).toEqual({ type: 'agentMetadata', metadata: {} })
    })
  })

  describe('fallback 帧', () => {
    it('合法 JSON → 解析', () => {
      const fb = { requestedModel: 'a', fallback: 'b' }
      expect(mapFrame('fallback', JSON.stringify(fb))).toEqual({ type: 'fallback', fallback: fb })
    })
    it('非法 JSON → 默认空串兜底', () => {
      expect(mapFrame('fallback', 'bad')).toEqual({
        type: 'fallback',
        fallback: { requestedModel: '', fallback: '' },
      })
    })
  })

  describe('canceled 帧', () => {
    it('合法 JSON → 取 reason', () => {
      expect(mapFrame('canceled', JSON.stringify({ reason: 'NAVIGATE_AWAY' }))).toEqual({
        type: 'canceled',
        reason: 'NAVIGATE_AWAY',
      })
    })
    it('非法 JSON → USER_ABORT 兜底', () => {
      expect(mapFrame('canceled', 'bad')).toEqual({ type: 'canceled', reason: 'USER_ABORT' })
    })
  })

  describe('error 帧', () => {
    it('合法 JSON → 取 error/message', () => {
      expect(mapFrame('error', JSON.stringify({ error: 'E', message: 'M' }))).toEqual({
        type: 'error',
        error: 'E',
        message: 'M',
      })
    })
    it('非法 JSON → UNKNOWN/生成失败 兜底', () => {
      expect(mapFrame('error', 'bad')).toEqual({
        type: 'error',
        error: 'UNKNOWN',
        message: '生成失败',
      })
    })
  })

  it('未知 event 名 → null（前向兼容）', () => {
    expect(mapFrame('future-event', 'payload')).toBeNull()
  })
})

describe('parseJson', () => {
  it('合法 JSON → 解析', () => {
    expect(parseJson<{ a: number }>('{"a":1}')).toEqual({ a: 1 })
  })
  it('非法 JSON → null', () => {
    expect(parseJson('not-json')).toBeNull()
  })
  it('空串 → null', () => {
    expect(parseJson('')).toBeNull()
  })
})

describe('parseEventBlock', () => {
  it('纯内容块（data:）→ content 帧', () => {
    expect(parseEventBlock('data: hello')).toEqual({ type: 'content', chunk: 'hello' })
  })

  it('event + data → 对应类型帧', () => {
    expect(parseEventBlock('event: reasoning\ndata: think')).toEqual({
      type: 'reasoning',
      chunk: 'think',
    })
  })

  it('多行 data → 以 \\n 拼接', () => {
    expect(parseEventBlock('data: line1\ndata: line2')).toEqual({
      type: 'content',
      chunk: 'line1\nline2',
    })
  })

  it('忽略 id:/retry:/注释行，仅取 event/data', () => {
    const block = 'id: 5\nevent: references\n: comment\ndata: []'
    expect(parseEventBlock(block)).toEqual({ type: 'references', references: [] })
  })

  it('无 data 行 → null', () => {
    expect(parseEventBlock('event: references')).toBeNull()
  })

  it('data: 后无空格仍能解析', () => {
    // replace(/^ /, '') 仅去前导一个空格；无空格原样保留
    expect(parseEventBlock('data:no-space')).toEqual({ type: 'content', chunk: 'no-space' })
  })
})
