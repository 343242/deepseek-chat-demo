import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import utc from 'dayjs/plugin/utc'
import 'dayjs/locale/zh-cn'

dayjs.extend(relativeTime)
dayjs.extend(utc)
dayjs.locale('zh-cn')

/** 时间格式化（DS §13.3） */
export const time = {
  /** 相对时间：3 分钟前 / 2 小时前（DS §13.3 相对） */
  fromNow(iso?: string | null): string {
    if (!iso) return ''
    return dayjs(iso).fromNow()
  },
  /** 会话列表/表格用：今天显示 HH:mm，昨天显示"昨天"，更早显示 MM-DD（DS §13.3） */
  short(iso?: string | null): string {
    if (!iso) return ''
    const d = dayjs(iso)
    const now = dayjs()
    if (d.isSame(now, 'day')) return d.format('HH:mm')
    if (d.isSame(now.subtract(1, 'day'), 'day')) return '昨天'
    if (d.isSame(now, 'year')) return d.format('MM-DD')
    return d.format('YYYY-MM-DD')
  },
  /** 完整时间：2026-06-20 14:30 */
  full(iso?: string | null): string {
    if (!iso) return ''
    return dayjs(iso).format('YYYY-MM-DD HH:mm')
  },
  /** 是否今天 */
  isToday(iso?: string | null): boolean {
    return !!iso && dayjs(iso).isSame(dayjs(), 'day')
  },
  /** 是否昨天 */
  isYesterday(iso?: string | null): boolean {
    return !!iso && dayjs(iso).isSame(dayjs().subtract(1, 'day'), 'day')
  },
  /** 是否 7 天内（不含今昨） */
  isThisWeek(iso?: string | null): boolean {
    if (!iso) return false
    const d = dayjs(iso)
    const now = dayjs()
    return d.isAfter(now.subtract(7, 'day')) && !this.isToday(iso) && !this.isYesterday(iso)
  },
  /** 两个时间间隔是否 ≥ 10 分钟（消息流时间分组，wireframe §3.3） */
  gapMinutes(a?: string | null, b?: string | null, threshold = 10): boolean {
    if (!a || !b) return true
    return Math.abs(dayjs(a).diff(dayjs(b), 'minute')) >= threshold
  },
}

/** 文件大小格式化（DS §13.4） */
export function formatFileSize(bytes?: number | null): string {
  if (bytes == null) return '-'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let n = bytes / 1024
  let i = 0
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i++
  }
  return `${n.toFixed(n < 10 ? 1 : 0)} ${units[i]}`
}

/** 耗时毫秒 → 可读（DS §11.3.7 元信息行） */
export function formatDuration(ms?: number | null): string {
  if (ms == null) return ''
  if (ms < 1000) return `${ms}ms`
  const s = ms / 1000
  if (s < 60) return `${s.toFixed(1)}s`
  const m = Math.floor(s / 60)
  return `${m}m${Math.round(s % 60)}s`
}

/** 取文件扩展名（小写） */
export function extOf(fileName: string): string {
  const i = fileName.lastIndexOf('.')
  return i >= 0 ? fileName.slice(i + 1).toLowerCase() : ''
}

/** 上传速度/剩余时间格式 */
export function formatSpeed(bytesPerSec: number): string {
  return `${formatFileSize(bytesPerSec)}/s`
}
