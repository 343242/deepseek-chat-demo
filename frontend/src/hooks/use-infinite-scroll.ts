import { useEffect, useRef, type RefObject } from 'react'

interface UseInfiniteScrollOptions {
  /** 哨兵可见时触发（已内置 hasMore/loading 守卫，调用方无需再判） */
  onLoadMore: () => void
  /** 是否还有更多；为 false 时不观察 */
  hasMore: boolean
  /** 是否正在加载；加载中不重复触发 */
  loading: boolean
}

/**
 * 无限滚动 hook（FE-001 DRY 收敛）。
 * 返回挂到「哨兵节点」的 ref；IntersectionObserver 监听其可见性，
 * 满足 `hasMore && !loading` 时调 onLoadMore。
 *
 * 调用方需把 onLoadMore 包成 useCallback（依赖变化时随 effect 重新订阅，
 * 与原内联实现语义一致）。
 */
export function useInfiniteScroll({
  onLoadMore,
  hasMore,
  loading,
}: UseInfiniteScrollOptions): RefObject<HTMLDivElement | null> {
  const sentinelRef = useRef<HTMLDivElement | null>(null)
  useEffect(() => {
    const el = sentinelRef.current
    if (!el || !hasMore) return
    const ob = new IntersectionObserver((entries) => {
      if (entries[0].isIntersecting && !loading) onLoadMore()
    })
    ob.observe(el)
    return () => ob.disconnect()
  }, [hasMore, loading, onLoadMore])
  return sentinelRef
}
