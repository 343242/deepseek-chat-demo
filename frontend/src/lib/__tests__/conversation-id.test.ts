import { describe, it, expect } from 'vitest'
import { toRawConversationId, buildIsolatedId, newRawId } from '../conversation-id'

describe('toRawConversationId', () => {
  it('已知 userId：剥离 u_{userId}_ 前缀', () => {
    expect(toRawConversationId('u_5_abc123', 5)).toBe('abc123')
  })

  it('userId 不匹配前缀：走兜底正则仍能剥离', () => {
    // 前缀 u_6_ 不匹配，但正则 ^u_(\d+)_(.+)$ 命中
    expect(toRawConversationId('u_5_abc', 6)).toBe('abc')
  })

  it('不传 userId：兜底正则剥离', () => {
    expect(toRawConversationId('u_42_xyz-uuid')).toBe('xyz-uuid')
  })

  it('非 isolated 形态（无前缀）：原样返回', () => {
    expect(toRawConversationId('plain-id')).toBe('plain-id')
  })

  it('null/undefined/空串 → null', () => {
    expect(toRawConversationId(null)).toBeNull()
    expect(toRawConversationId(undefined)).toBeNull()
    expect(toRawConversationId('')).toBeNull()
  })
})

describe('buildIsolatedId', () => {
  it('raw + userId → u_{userId}_{raw}', () => {
    expect(buildIsolatedId('raw1', 5)).toBe('u_5_raw1')
  })
})

describe('newRawId', () => {
  it('返回非空字符串', () => {
    expect(typeof newRawId()).toBe('string')
    expect(newRawId().length).toBeGreaterThan(0)
  })

  it('多次调用产生不同值', () => {
    const a = newRawId()
    const b = newRawId()
    expect(a).not.toBe(b)
  })
})
