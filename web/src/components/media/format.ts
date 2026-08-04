/**
 * 媒体详情页展示格式化工具（前端负责全部格式化）
 */

/**
 * 时长格式化为「x小时x分钟」，不足 1 分钟显示「x秒」
 */
export function formatDurationText(ms: number | null | undefined): string | null {
  if (ms == null || ms <= 0) return null
  const totalMinutes = Math.round(ms / 60000)
  if (totalMinutes <= 0) return `${Math.round(ms / 1000)}秒`
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  if (hours === 0) return `${minutes}分钟`
  return minutes === 0 ? `${hours}小时` : `${hours}小时${minutes}分钟`
}

/**
 * 播放位置格式化为 mm:ss 或 h:mm:ss
 */
export function formatPosition(ms: number | null | undefined): string {
  if (ms == null || ms <= 0) return '00:00'
  const totalSeconds = Math.floor(ms / 1000)
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  const mm = String(minutes).padStart(2, '0')
  const ss = String(seconds).padStart(2, '0')
  return hours > 0 ? `${hours}:${mm}:${ss}` : `${mm}:${ss}`
}

const MINUTE_MS = 60_000
const HOUR_MS = 60 * MINUTE_MS
const DAY_MS = 24 * HOUR_MS
const MONTH_MS = 30 * DAY_MS
const YEAR_MONTHS_MS = 12 * MONTH_MS

/** 后端时间字符串形态：yyyy-MM-dd 分隔符可为空格或 T */
const GMT8_DATE_TIME_PATTERN = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})$/

/**
 * 将后端 GMT+8 的 `yyyy-MM-dd HH:mm:ss` 时间字符串解析为时间戳（毫秒）。
 * 无法解析或日期不合法返回 null；解析结果与字符串所表达的 GMT+8 墙上时间一一对应。
 */
export function parseGmt8DateTime(value: string | null | undefined): number | null {
  if (!value) return null
  const match = GMT8_DATE_TIME_PATTERN.exec(value)
  if (!match) return null
  const [year, month, day, hour, minute, second] = match.slice(1).map(Number)
  if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 59) return null
  // 先按 UTC 构造墙上时间，再折算回 GMT+8 对应的真实时间戳
  const utcMs = Date.UTC(year, month - 1, day, hour, minute, second)
  const check = new Date(utcMs)
  if (
    check.getUTCFullYear() !== year ||
    check.getUTCMonth() !== month - 1 ||
    check.getUTCDate() !== day ||
    check.getUTCHours() !== hour ||
    check.getUTCMinutes() !== minute ||
    check.getUTCSeconds() !== second
  ) {
    return null
  }
  return utcMs - 8 * HOUR_MS
}

/**
 * 相对入库时间文本（影视首页最新电影/最新剧集卡片）。
 * 入参为后端 GMT+8 的 `yyyy-MM-dd HH:mm:ss`，结果按浏览器当前时间计算；
 * null / 非法 / 未来 / 不足 1 分钟显示「刚刚」，之后依次为分钟、小时、天（30 天内）、月（12 个月内）、年。
 * nowMs 缺省取当前时间，测试可注入。
 */
export function formatMediaRelativeTime(addedTime: string | null, nowMs?: number): string {
  const added = parseGmt8DateTime(addedTime)
  if (added == null) return '刚刚'
  const now = nowMs ?? Date.now()
  const diff = now - added
  if (diff < MINUTE_MS) return '刚刚'
  if (diff < HOUR_MS) return `${Math.floor(diff / MINUTE_MS)}分钟前`
  if (diff < DAY_MS) return `${Math.floor(diff / HOUR_MS)}小时前`
  if (diff < MONTH_MS) return `${Math.floor(diff / DAY_MS)}天前`
  if (diff < YEAR_MONTHS_MS) return `${Math.floor(diff / MONTH_MS)}个月前`
  return `${Math.floor(diff / YEAR_MONTHS_MS)}年前`
}
