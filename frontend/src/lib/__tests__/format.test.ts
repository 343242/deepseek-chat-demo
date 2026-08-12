import { describe, it, expect } from 'vitest'
import { time, formatFileSize, formatDuration } from '../format'

describe('formatFileSize', () => {
  it('null/undefined → -', () => {
    expect(formatFileSize(null)).toBe('-')
    expect(formatFileSize(undefined)).toBe('-')
  })
  it('< 1024 → 字节', () => {
    expect(formatFileSize(0)).toBe('0 B')
    expect(formatFileSize(500)).toBe('500 B')
    expect(formatFileSize(1023)).toBe('1023 B')
  })
  it('KB 边界（n<10 保留 1 位小数）', () => {
    expect(formatFileSize(1024)).toBe('1.0 KB')
    expect(formatFileSize(5 * 1024)).toBe('5.0 KB')
  })
  it('MB 进位', () => {
    expect(formatFileSize(1024 * 1024)).toBe('1.0 MB')
    expect(formatFileSize(5 * 1024 * 1024)).toBe('5.0 MB')
  })
  it('n≥10 不保留小数', () => {
    // 100 KB = 102400 → n=100 → '100 KB'
    expect(formatFileSize(100 * 1024)).toBe('100 KB')
  })
})

describe('formatDuration', () => {
  it('null/undefined → 空串', () => {
    expect(formatDuration(null)).toBe('')
    expect(formatDuration(undefined)).toBe('')
  })
  it('< 1000ms → 毫秒', () => {
    expect(formatDuration(500)).toBe('500ms')
  })
  it('< 60s → 秒（1 位小数）', () => {
    expect(formatDuration(1000)).toBe('1.0s')
    expect(formatDuration(1500)).toBe('1.5s')
  })
  it('≥ 60s → 分秒', () => {
    expect(formatDuration(60_000)).toBe('1m0s')
    expect(formatDuration(90_000)).toBe('1m30s')
  })
})

describe('time', () => {
  it('short(null/undefined) → 空串', () => {
    expect(time.short(null)).toBe('')
    expect(time.short(undefined)).toBe('')
  })

  it('isToday(null) → false', () => {
    expect(time.isToday(null)).toBe(false)
  })

  describe('gapMinutes', () => {
    it('任一为空 → true（视为需分隔）', () => {
      expect(time.gapMinutes(null, null)).toBe(true)
      expect(time.gapMinutes('2026-01-01T00:00:00Z', null)).toBe(true)
    })
    it('间隔 ≥ 10 分钟 → true', () => {
      const a = '2026-08-12T10:00:00Z'
      const b = '2026-08-12T10:15:00Z'
      expect(time.gapMinutes(a, b)).toBe(true)
    })
    it('间隔 < 10 分钟 → false', () => {
      const a = '2026-08-12T10:00:00Z'
      const b = '2026-08-12T10:05:00Z'
      expect(time.gapMinutes(a, b)).toBe(false)
    })
    it('自定义阈值（默认 10 判 false，下调到 5 后 6 分钟判 true）', () => {
      const a = '2026-08-12T10:00:00Z'
      const b = '2026-08-12T10:06:00Z'
      expect(time.gapMinutes(a, b)).toBe(false) // 默认阈值 10
      expect(time.gapMinutes(a, b, 5)).toBe(true) // 下调到 5
    })
  })
})
