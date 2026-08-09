/**
 * 全局播放配置缓存（ADR 0024）
 * - 模块级 promise 单例：并发调用共享同一请求，成功后缓存，失败清空可重试
 * - 播放流程（useMediaPlayback.start）在加载播放信息时并行拉取一次，此后同步读取
 */
import { fetchPlaybackConfig } from '@/api/media'
import type { MediaPlaybackConfigVo } from '@/types/media'

let configPromise: Promise<MediaPlaybackConfigVo> | null = null
let resolvedConfig: MediaPlaybackConfigVo | null = null

/**
 * 加载全局播放配置：多次调用共享同一请求（首次发起，后续复用缓存 promise）。
 * 失败时清空缓存，下次调用重新请求。
 */
export function loadPlaybackConfig(): Promise<MediaPlaybackConfigVo> {
  if (!configPromise) {
    configPromise = fetchPlaybackConfig()
      .then((config) => {
        resolvedConfig = config
        return config
      })
      .catch((error) => {
        configPromise = null
        throw error
      })
  }
  return configPromise
}

/**
 * 同步读取已加载的配置（loadPlaybackConfig 完成后可用），未加载完成返回 null。
 */
export function getPlaybackConfig(): MediaPlaybackConfigVo | null {
  return resolvedConfig
}

/**
 * 清空模块级缓存（测试用，或登出切换用户后重新拉取）。
 */
export function resetPlaybackConfigCache(): void {
  configPromise = null
  resolvedConfig = null
}
