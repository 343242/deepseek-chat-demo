import { describe, it, expect } from 'vitest'
import { flattenMessages } from '../flatten-messages'
import type { MessageVO } from '@/types/chat'

function user(id: number, content: string, createdAt: string, children: MessageVO[] = []): MessageVO {
  return { id, parentId: null, role: 'USER', content, status: 'FINISHED', createdAt, children }
}

function assistant(id: number, parentId: number, content: string, createdAt: string): MessageVO {
  return { id, parentId, role: 'ASSISTANT', content, status: 'FINISHED', createdAt }
}

describe('flattenMessages', () => {
  it('单轮：user → assistant 摊平为 [user, assistant]', () => {
    const tree = [user(1, '你好', 't0', [assistant(2, 1, '你好！', 't1')])]
    const flat = flattenMessages(tree)
    expect(flat.map((m) => m.role)).toEqual(['USER', 'ASSISTANT'])
    expect(flat.map((m) => m.id)).toEqual([1, 2])
  })

  it('多轮：按 [user, assistant, user, assistant] 顺序展开', () => {
    const tree = [
      user(1, 'q1', 't0', [assistant(2, 1, 'a1', 't1')]),
      user(3, 'q2', 't2', [assistant(4, 3, 'a2', 't3')]),
    ]
    const flat = flattenMessages(tree)
    expect(flat.map((m) => m.id)).toEqual([1, 2, 3, 4])
  })

  it('剥离 children：扁平元素不再携带嵌套子消息', () => {
    const tree = [user(1, 'q', 't0', [assistant(2, 1, 'a', 't1')])]
    const flat = flattenMessages(tree)
    expect(flat[0]?.children).toBeUndefined()
    expect(flat[1]?.children).toBeUndefined()
  })

  it('多分支（重新生成）：同 parent 的多条 assistant 按 createdAt 升序', () => {
    const tree = [user(1, 'q', 't0', [
      assistant(3, 1, 'later', 't2'),
      assistant(2, 1, 'earlier', 't1'),
    ])]
    const flat = flattenMessages(tree)
    expect(flat.map((m) => [m.id, m.content])).toEqual([
      [1, 'q'],
      [2, 'earlier'],
      [3, 'later'],
    ])
  })

  it('仅有 user（无回复）的轮次：只输出 user', () => {
    const flat = flattenMessages([user(1, 'q', 't0', [])])
    expect(flat.map((m) => m.role)).toEqual(['USER'])
  })

  it('空输入：null / undefined / [] → []', () => {
    expect(flattenMessages(null)).toEqual([])
    expect(flattenMessages(undefined)).toEqual([])
    expect(flattenMessages([])).toEqual([])
  })
})
