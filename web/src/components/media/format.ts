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
