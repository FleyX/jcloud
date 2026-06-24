/**
 * 设备类型判定工具
 *
 * 项目采用运行时 UA 判定来区分 PC 端与移动端，不使用响应式布局。
 * 平板（含 iPad）归入 PC 端处理。
 */

export type DeviceChannel = 'pc' | 'mobile'

/**
 * 根据 navigator.userAgent 判定当前设备渠道
 */
export function detectDeviceChannel(): DeviceChannel {
  const ua = navigator.userAgent

  // iPad 明确归为 PC 端；其他常见移动标识归为移动端
  if (/iPad/i.test(ua)) {
    return 'pc'
  }

  if (/Mobi|Android|iPhone|iPod|BlackBerry|IEMobile|Opera Mini/i.test(ua)) {
    return 'mobile'
  }

  return 'pc'
}

/**
 * 是否为移动端
 */
export function isMobile(): boolean {
  return detectDeviceChannel() === 'mobile'
}

/**
 * 预加载所有视图组件映射
 *
 * 使用 import.meta.glob 避免 dev 环境下变量动态 import 的深度限制，
 * 同时保证 production 构建正常分块。
 */
const viewModules = import.meta.glob('../views/**/*.vue')

const viewModuleMap = new Map<string, () => Promise<unknown>>()
for (const [key, importer] of Object.entries(viewModules)) {
  // key 示例："../views/pc/auth/Login.vue"
  const normalized = key.replace('../views/', '').replace('.vue', '')
  viewModuleMap.set(normalized, importer)
}

/**
 * 路由视图代理：根据当前设备返回对应的视图组件异步加载函数
 *
 * @param path 视图在 views/pc/ 或 views/mobile/ 下的相对路径（不含 .vue 后缀）
 */
export function deviceView(path: string) {
  const key = `${detectDeviceChannel()}/${path}`
  const importer = viewModuleMap.get(key)

  if (!importer) {
    throw new Error(`Device view not found: ${key}.vue`)
  }

  return importer as () => Promise<{ default: unknown }>
}
