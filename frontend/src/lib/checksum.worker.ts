/// <reference lib="webworker" />
import { sha256Hex } from './checksum-core'

/**
 * 校验和 Worker（FE-007）：把 SHA-256 计算移出主线程，避免大文件阻塞 UI。
 * 入参 { id, blob }，回 { id, checksum }。
 * blob 经结构化克隆传递（廉价句柄，无需 transfer）。
 */
self.onmessage = async (e: MessageEvent<{ id: number; blob: Blob }>) => {
  const { id, blob } = e.data
  try {
    const checksum = await sha256Hex(blob)
    ;(self as unknown as Worker).postMessage({ id, checksum })
  } catch (err) {
    // 失败也回包（checksum 为空串），避免客户端 promise 永久挂起
    ;(self as unknown as Worker).postMessage({ id, checksum: '', error: (err as Error).message })
  }
}
