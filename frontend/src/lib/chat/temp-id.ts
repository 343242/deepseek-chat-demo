/**
 * 临时消息 id 生成（FE-006：从 chat-store 模块作用域抽出，独立可测）。
 *
 * 后端持久化消息 id 为正整数；本地乐观消息用负数占位，避免与真实 id 冲突。
 * 单调递减（-1, -2, -3 …）。
 */
let tempIdSeq = -1

/** 下一个临时 id（负数） */
export const nextTempId = (): number => tempIdSeq--

/** 仅供测试重置序列 */
export function __resetTempIdForTest(): void {
  tempIdSeq = -1
}
