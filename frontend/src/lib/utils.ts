import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/** 合并 Tailwind 类名（shadcn 标配：clsx + tailwind-merge） */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/** 短延迟（常用于抖动回弹等微交互） */
export const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))
