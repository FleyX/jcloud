import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  fetchPlaybackConfig: vi.fn(),
}))

vi.mock('@/api/media', () => mocks)

import { getPlaybackConfig, loadPlaybackConfig, resetPlaybackConfigCache } from './usePlaybackConfig'
import type { MediaPlaybackConfigVo } from '@/types/media'

function buildConfig(): MediaPlaybackConfigVo {
  return {
    bitrateTiers: [
      { key: 'original', label: '原画', kbps: null, maxHeight: null },
      { key: '2000-720', label: '2M · 720p', kbps: 2000, maxHeight: 720 },
    ],
    directPlay: { containers: ['mp4'], videoCodecs: ['h264'], audioCodecs: ['aac'] },
    remux: { videoCopyCodecs: ['h264', 'hevc'], audioCopyCodecs: ['aac'] },
    finishedRatio: 0.95,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  resetPlaybackConfigCache()
})

describe('usePlaybackConfig 模块级单例缓存', () => {
  it('并发多次调用共享同一请求，成功后同步读取可用', async () => {
    mocks.fetchPlaybackConfig.mockResolvedValue(buildConfig())

    const [first, second, third] = await Promise.all([
      loadPlaybackConfig(),
      loadPlaybackConfig(),
      loadPlaybackConfig(),
    ])

    expect(mocks.fetchPlaybackConfig).toHaveBeenCalledTimes(1)
    expect(first).toBe(second)
    expect(third).toBe(second)
    expect(getPlaybackConfig()).toEqual(buildConfig())
  })

  it('失败时清空缓存不驻留拒绝态，下次调用可重试', async () => {
    mocks.fetchPlaybackConfig.mockRejectedValueOnce(new Error('network'))

    await expect(loadPlaybackConfig()).rejects.toThrow('network')
    expect(mocks.fetchPlaybackConfig).toHaveBeenCalledTimes(1)
    expect(getPlaybackConfig()).toBeNull()

    mocks.fetchPlaybackConfig.mockResolvedValue(buildConfig())
    const config = await loadPlaybackConfig()
    expect(mocks.fetchPlaybackConfig).toHaveBeenCalledTimes(2)
    expect(config).toEqual(buildConfig())
    expect(getPlaybackConfig()).toEqual(buildConfig())
  })

  it('成功后重复加载不再发起请求', async () => {
    mocks.fetchPlaybackConfig.mockResolvedValue(buildConfig())

    await loadPlaybackConfig()
    await loadPlaybackConfig()

    expect(mocks.fetchPlaybackConfig).toHaveBeenCalledTimes(1)
  })
})
