import type { MessageVO, RenderMessage } from '@/types/chat'

/**
 * 把后端「一层子消息树」摊平为线性渲染序列。
 *
 * 动机：detail / messages 接口返回的是树（USER 为根，ASSISTANT 嵌在 children，仅一层），
 * 而前端消息流是扁平数组（与实时流式一致：user / assistant 平级）。若直接把树当扁平列表
 * 渲染，只会画出顶层 USER 节点、丢掉嵌套在 children 里的 ASSISTANT —— 即「历史只见提问不见回复」。
 * 在加载历史的边界做一次性摊平，使「读历史」与「实时流式」两条渲染路径形状统一。
 *
 * 多分支（同一 USER 下多条 ASSISTANT，如「重新生成」）按 createdAt 升序依次展开。
 */
export function flattenMessages(roots: MessageVO[] | undefined | null): RenderMessage[] {
  if (!roots || roots.length === 0) return []
  const flat: RenderMessage[] = []
  for (const root of roots) {
    flat.push(stripChildren(root))
    if (root.children?.length) {
      const sorted = [...root.children].sort((a, b) => a.createdAt.localeCompare(b.createdAt))
      for (const child of sorted) flat.push(stripChildren(child))
    }
  }
  return flat
}

/** 剥离 children：扁平渲染序列中的元素不应再携带嵌套子消息 */
function stripChildren(m: MessageVO): RenderMessage {
  const rest = { ...m }
  delete rest.children
  return rest
}
