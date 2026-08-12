import ChecksumWorker from './checksum.worker.ts?worker'

/**
 * 校验和客户端（FE-007）：单例 Worker + promise 化。
 *
 * 用法：`const hex = await computeChecksum(fileOrBlob)` —— 返回 SHA-256 小写 hex。
 * 计算在 Worker 内进行，主线程不阻塞（UI/输入/动画保持响应）。
 */

let worker: Worker | null = null
let seq = 0
const pending = new Map<number, (checksum: string) => void>()

function getWorker(): Worker {
  if (!worker) {
    worker = new ChecksumWorker()
    worker.onmessage = (e: MessageEvent<{ id: number; checksum: string }>) => {
      const { id, checksum } = e.data
      pending.get(id)?.(checksum)
      pending.delete(id)
    }
    // Worker 异常兜底：reject 所有在途请求，避免上传流程卡死
    worker.onerror = (e) => {
      for (const resolve of pending.values()) resolve('')
      pending.clear()
      // 不吞原始错误，便于控制台定位
      console.error('[checksum worker] fatal', e.message)
    }
  }
  return worker
}

/** 计算文件/分片的 SHA-256（hex，小写），在 Worker 内流式计算不阻塞主线程 */
export function computeChecksum(blob: Blob): Promise<string> {
  const id = seq++
  return new Promise<string>((resolve) => {
    pending.set(id, resolve)
    getWorker().postMessage({ id, blob })
  })
}

export { CHECKSUM_ALGORITHM } from './checksum-core'
