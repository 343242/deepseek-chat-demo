import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/** 合并 Tailwind 类名（shadcn 标配：clsx + tailwind-merge） */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/** 短延迟（常用于抖动回弹等微交互） */
export const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

/** Map 取值或初始化：无值时用 create() 建立并写入（消除 map.get(k)! 非空断言，FE-019） */
export function getOrCreate<K, V>(map: Map<K, V>, key: K, create: () => V): V {
  const existing = map.get(key)
  if (existing !== undefined) return existing
  const created = create()
  map.set(key, created)
  return created
}
