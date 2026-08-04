import { describe, expect, it } from 'vitest'
import { formatMediaRelativeTime, parseGmt8DateTime } from './format'

const HOUR_MS = 3_600_000
const DAY_MS = 24 * HOUR_MS
const MONTH_MS = 30 * DAY_MS
const YEAR_MS = 365 * DAY_MS

/** 将时间戳还原为 GMT+8 墙上时钟的 yyyy-MM-dd HH:mm:ss 字符串（与后端约定一致） */
function gmt8String(ms: number): string {
  const d = new Date(ms + 8 * HOUR_MS)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())} ${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())}`
}

const NOW = Date.UTC(2023, 2, 4, 12, 0, 0)

describe('parseGmt8DateTime', () => {
  it('parses yyyy-MM-dd HH:mm:ss as GMT+8', () => {
    // 2023-03-04 10:00:00 GMT+8 即 2023-03-04 02:00:00 UTC
    expect(parseGmt8DateTime('2023-03-04 10:00:00')).toBe(Date.UTC(2023, 2, 4, 2, 0, 0))
  })

  it('also accepts an ISO-style T separator', () => {
    expect(parseGmt8DateTime('2023-03-04T10:00:00')).toBe(Date.UTC(2023, 2, 4, 2, 0, 0))
  })

  it('round-trips through the gmt8String helper', () => {
    expect(parseGmt8DateTime(gmt8String(NOW))).toBe(NOW)
  })

  it('returns null for null/empty/whitespace input', () => {
    expect(parseGmt8DateTime(null)).toBeNull()
    expect(parseGmt8DateTime(undefined)).toBeNull()
    expect(parseGmt8DateTime('')).toBeNull()
    expect(parseGmt8DateTime('  ')).toBeNull()
  })

  it('returns null for malformed strings', () => {
    expect(parseGmt8DateTime('2023/03/04 10:00:00')).toBeNull()
    expect(parseGmt8DateTime('2023-03-04')).toBeNull()
    expect(parseGmt8DateTime('2023-03-04 10:00')).toBeNull()
    expect(parseGmt8DateTime('2023-13-01 10:00:00')).toBeNull()
    expect(parseGmt8DateTime('2023-00-01 10:00:00')).toBeNull()
    expect(parseGmt8DateTime('2023-03-32 10:00:00')).toBeNull()
    expect(parseGmt8DateTime('2023-03-04 24:00:00')).toBeNull()
    expect(parseGmt8DateTime('2023-03-04 10:60:00')).toBeNull()
    expect(parseGmt8DateTime('2023-03-04 10:00:61')).toBeNull()
    // 2 月不存在 30 日，Date.UTC 会进位，必须被识别为非法
    expect(parseGmt8DateTime('2023-02-30 10:00:00')).toBeNull()
  })

  it('accepts valid leap-day', () => {
    expect(parseGmt8DateTime('2024-02-29 00:00:00')).not.toBeNull()
  })
})

describe('formatMediaRelativeTime', () => {
  it('returns 刚刚 for null or invalid input', () => {
    expect(formatMediaRelativeTime(null, NOW)).toBe('刚刚')
    expect(formatMediaRelativeTime('not-a-date', NOW)).toBe('刚刚')
    expect(formatMediaRelativeTime('2023-02-30 10:00:00', NOW)).toBe('刚刚')
  })

  it('returns 刚刚 for future times', () => {
    expect(formatMediaRelativeTime(gmt8String(NOW + 5 * 60_000), NOW)).toBe('刚刚')
    expect(formatMediaRelativeTime(gmt8String(NOW + DAY_MS), NOW)).toBe('刚刚')
  })

  it('returns 刚刚 for less than one minute', () => {
    expect(formatMediaRelativeTime(gmt8String(NOW - 30_000), NOW)).toBe('刚刚')
    expect(formatMediaRelativeTime(gmt8String(NOW - 59_000), NOW)).toBe('刚刚')
  })

  it('formats minutes', () => {
    expect(formatMediaRelativeTime(gmt8String(NOW - 60_000), NOW)).toBe('1分钟前')
    expect(formatMediaRelativeTime(gmt8String(NOW - 59 * 60_000), NOW)).toBe('59分钟前')
  })

  it('formats hours', () => {
    expect(formatMediaRelativeTime(gmt8String(NOW - HOUR_MS), NOW)).toBe('1小时前')
    expect(formatMediaRelativeTime(gmt8String(NOW - 23 * HOUR_MS), NOW)).toBe('23小时前')
  })

  it('formats days within 30 days', () => {
    expect(formatMediaRelativeTime(gmt8String(NOW - DAY_MS), NOW)).toBe('1天前')
    expect(formatMediaRelativeTime(gmt8String(NOW - 29 * DAY_MS), NOW)).toBe('29天前')
  })

  it('formats months within 12 months', () => {
    expect(formatMediaRelativeTime(gmt8String(NOW - MONTH_MS), NOW)).toBe('1个月前')
    expect(formatMediaRelativeTime(gmt8String(NOW - 11 * MONTH_MS), NOW)).toBe('11个月前')
  })

  it('formats years from 12 months onward', () => {
    expect(formatMediaRelativeTime(gmt8String(NOW - 12 * MONTH_MS), NOW)).toBe('1年前')
    expect(formatMediaRelativeTime(gmt8String(NOW - YEAR_MS), NOW)).toBe('1年前')
    expect(formatMediaRelativeTime(gmt8String(NOW - 2 * YEAR_MS), NOW)).toBe('2年前')
  })

  it('falls back to the current time when nowMs is omitted', () => {
    const before = Date.now()
    const result = formatMediaRelativeTime(gmt8String(Date.now()))
    const after = Date.now()
    expect(result).toBe('刚刚')
    expect(before).toBeLessThanOrEqual(after)
  })
})
