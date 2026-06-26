/**
 * 存储容量单位换算工具。
 * 后端统一以字节（Byte）存储容量，前端按 MB / GB / TB 展示与编辑。
 */
export type StorageUnit = 'MB' | 'GB' | 'TB'

export const STORAGE_UNITS: StorageUnit[] = ['MB', 'GB', 'TB']

const UNIT_FACTOR: Record<StorageUnit, number> = {
  MB: 1024 * 1024,
  GB: 1024 * 1024 * 1024,
  TB: 1024 * 1024 * 1024 * 1024,
}

export function normalizeStorageUnit(unit: string): StorageUnit {
  const upper = unit?.trim().toUpperCase()
  return STORAGE_UNITS.includes(upper as StorageUnit) ? (upper as StorageUnit) : 'GB'
}

export function getStorageUnitFactor(unit: string): number {
  return UNIT_FACTOR[normalizeStorageUnit(unit)] ?? UNIT_FACTOR.GB
}

/**
 * 将字节数转换为指定单位的展示数值。
 *
 * @param bytes 字节数
 * @param unit 目标单位
 * @returns 目标单位下的数值字符串，0 或无效输入返回 "0"
 */
export function bytesToUnitValue(bytes: string | number | undefined, unit: string): string {
  const bytesNum = Number(bytes) || 0
  if (bytesNum <= 0) return '0'
  return String(bytesNum / getStorageUnitFactor(unit))
}

/**
 * 将指定单位的数值转换为字节数。
 *
 * @param value 单位数值
 * @param unit 源单位
 * @returns 字节数字符串，0 或无效输入返回 "0"
 */
export function unitValueToBytes(value: string | number, unit: string): string {
  const num = Number(value) || 0
  if (num <= 0) return '0'
  return String(num * getStorageUnitFactor(unit))
}
