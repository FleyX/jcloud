/**
 * 通用格式化工具
 */

/**
 * 毫秒时长 → 中文可读文本（如「2小时5分」「8分钟」）；空值返回 null。
 * 兼容后端 Long 序列化后的字符串与数字两种形态（影视模块两处卡片共用）。
 */
export function formatDuration(ms: number | string | null | undefined): string | null {
  if (ms === null || ms === undefined || ms === '') return null
  const totalMinutes = Math.floor(Number(ms) / 60000)
  if (Number.isNaN(totalMinutes) || totalMinutes < 1) return null
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  return hours > 0 ? `${hours}小时${minutes}分` : `${minutes}分钟`
}
