import { describe, it, expect } from 'vitest'
import { applyFrame, finalizeInProgress } from '../stream-reducer'
import type { RenderMessage, SseFrame } from '@/types/chat'
import type { Reference } from '@/types/document'

const ASSISTANT_ID = -2

function makeMessages(assistantOver: Partial<RenderMessage> = {}): RenderMessage[] {
  return [
    { id: -1, parentId: null, role: 'USER', content: 'hi', status: 'FINISHED', createdAt: 't0' },
    {
      id: ASSISTANT_ID,
      parentId: -1,
      role: 'ASSISTANT',
      content: '',
      status: 'IN_PROGRESS',
      createdAt: 't1',
      ...assistantOver,
    },
  ]
}

const REF: Reference = {
  refNumber: 1,
  chunkId: 'c1',
  documentId: 'd1',
  fileName: 'a.pdf',
}

describe('applyFrame', () => {
  it('content：追加到 assistant 消息', () => {
    const out = applyFrame(makeMessages(), { type: 'content', chunk: 'Hel' }, ASSISTANT_ID)
    expect(out[1].content).toBe('Hel')
    const out2 = applyFrame(out, { type: 'content', chunk: 'lo' }, ASSISTANT_ID)
    expect(out2[1].content).toBe('Hello')
  })

  it('reasoning：追加（首帧无前缀 undefined）', () => {
    const out = applyFrame(makeMessages(), { type: 'reasoning', chunk: 'think' }, ASSISTANT_ID)
    expect(out[1].reasoning).toBe('think')
    const out2 = applyFrame(out, { type: 'reasoning', chunk: ' more' }, ASSISTANT_ID)
    expect(out2[1].reasoning).toBe('think more')
  })

  it('references：替换字段', () => {
    const out = applyFrame(makeMessages(), { type: 'references', references: [REF] }, ASSISTANT_ID)
    expect(out[1].references).toEqual([REF])
  })

  it('agentMetadata：写入元数据', () => {
    const meta = { intent: 'SEARCH', confidence: 0.8 }
    const out = applyFrame(makeMessages(), { type: 'agentMetadata', metadata: meta }, ASSISTANT_ID)
    expect(out[1].agentMetadata).toEqual(meta)
  })

  it('fallback：写入降级信息', () => {
    const fb = { requestedModel: 'a', fallback: 'b' }
    const out = applyFrame(makeMessages(), { type: 'fallback', fallback: fb }, ASSISTANT_ID)
    expect(out[1].fallback).toEqual(fb)
  })

  it('canceled：消息转 FINISHED', () => {
    const out = applyFrame(makeMessages(), { type: 'canceled', reason: 'USER_ABORT' }, ASSISTANT_ID)
    expect(out[1].status).toBe('FINISHED')
  })

  it('error：消息转 ERROR', () => {
    const frame: SseFrame = { type: 'error', error: 'E', message: '失败' }
    const out = applyFrame(makeMessages(), frame, ASSISTANT_ID)
    expect(out[1].status).toBe('ERROR')
  })
})

describe('applyFrame 不可变性与隔离', () => {
  it('非目标消息保持引用不变', () => {
    const before = makeMessages()
    const after = applyFrame(before, { type: 'content', chunk: 'x' }, ASSISTANT_ID)
    expect(after[0]).toBe(before[0]) // user 消息同一引用
    expect(after[1]).not.toBe(before[1]) // assistant 消息是新对象
  })

  it('不修改原数组（不可变）', () => {
    const before = makeMessages()
    applyFrame(before, { type: 'content', chunk: 'x' }, ASSISTANT_ID)
    expect(before[1].content).toBe('') // 原对象未被改
  })

  it('assistantId 不匹配时数组内容等价、目标消息不变', () => {
    const before = makeMessages()
    const after = applyFrame(before, { type: 'content', chunk: 'x' }, -999)
    expect(after[1].content).toBe('')
  })
})

describe('finalizeInProgress', () => {
  it('IN_PROGRESS → FINISHED', () => {
    const out = finalizeInProgress(makeMessages(), ASSISTANT_ID)
    expect(out[1].status).toBe('FINISHED')
  })

  it('已 FINISHED 的不变（幂等）', () => {
    const msgs = makeMessages({ status: 'FINISHED' })
    const out = finalizeInProgress(msgs, ASSISTANT_ID)
    expect(out[1].status).toBe('FINISHED')
  })

  it('ERROR 不被改成 FINISHED', () => {
    const msgs = makeMessages({ status: 'ERROR' })
    const out = finalizeInProgress(msgs, ASSISTANT_ID)
    expect(out[1].status).toBe('ERROR')
  })
})
