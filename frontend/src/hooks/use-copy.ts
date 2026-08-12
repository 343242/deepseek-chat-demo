import { useCallback, useState } from 'react'

interface UseCopyResult {
  copied: boolean
  copy: (text: string) => Promise<void>
}

/**
 * 复制到剪贴板 hook（FE-001 DRY 收敛）。
 * 成功后 copied 置 true，timeoutMs 后复位（默认 1500ms）。
 * 失败静默（navigator.clipboard 在非 HTTPS / 无权限时可能 reject，与原实现一致）。
 */
export function useCopy(timeoutMs = 1500): UseCopyResult {
  const [copied, setCopied] = useState(false)
  const copy = useCallback(
    async (text: string) => {
      try {
        await navigator.clipboard.writeText(text)
        setCopied(true)
        setTimeout(() => setCopied(false), timeoutMs)
      } catch {
        /* ignore */
      }
    },
    [timeoutMs],
  )
  return { copied, copy }
}
