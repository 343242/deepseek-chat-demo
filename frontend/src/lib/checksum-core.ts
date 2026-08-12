import { createSHA256, type IHasher } from 'hash-wasm'

/**
 * 校验和核心（纯逻辑、可单测，FE-007：MD5 → SHA-256 根因迁移）。
 *
 * 设计要点：
 * - 算法固定为 SHA-256（后端同步迁移，前端先行；见 CHECKSUM_ALGORITHM）。
 * - hash-wasm 增量 API（createSHA256().update()）+ Blob.stream() 分块读取，
 *   50MB 文件无需整块载入内存。
 * - 本模块刻意不引入 Worker / DOM 耦合，便于在 vitest(jsdom/node) 下对标准
 *   SHA-256 向量做等价校验（证明算法正确，后端校验可通过）。
 */

/** 当前使用的校验和算法（契约约定，前后端一致） */
export const CHECKSUM_ALGORITHM = 'SHA-256' as const

let hasherPromise: Promise<IHasher> | null = null
function getHasher(): Promise<IHasher> {
  // WASM 仅初始化一次，复用 hasher 实例
  if (!hasherPromise) hasherPromise = createSHA256()
  return hasherPromise
}

/**
 * 计算数据的 SHA-256（小写 hex）。
 * 接受 Blob（流式读取，适合大文件）或 ArrayBuffer/Uint8Array（直接喂入）。
 */
export async function sha256Hex(data: Blob | ArrayBuffer | Uint8Array): Promise<string> {
  const hasher = await getHasher()
  hasher.init()
  if (data instanceof Blob) {
    if (typeof data.stream === 'function') {
      // 现代浏览器/Node：流式分块读取（大文件不整块载入内存）
      const reader = data.stream().getReader()
      // eslint-disable-next-line no-constant-condition
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        hasher.update(value)
      }
    } else {
      // 兜底：少量运行时（如部分测试环境的 jsdom Blob）无 stream()，一次性读取
      hasher.update(new Uint8Array(await data.arrayBuffer()))
    }
  } else {
    hasher.update(data instanceof Uint8Array ? data : new Uint8Array(data))
  }
  return hasher.digest('hex')
}
