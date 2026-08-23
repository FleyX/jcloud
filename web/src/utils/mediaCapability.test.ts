import { afterEach, describe, expect, it, vi } from 'vitest'
import { canNativeDirectPlay } from './mediaCapability'

describe('canNativeDirectPlay', () => {
  const canPlayTypeSpy = vi.spyOn(HTMLVideoElement.prototype, 'canPlayType')

  afterEach(() => {
    canPlayTypeSpy.mockReset()
  })

  it('容器/编码缺失时放行直放，不触发探测', () => {
    expect(canNativeDirectPlay(null, 'hevc')).toBe(true)
    expect(canNativeDirectPlay('mp4', null)).toBe(true)
    expect(canPlayTypeSpy).not.toHaveBeenCalled()
  })

  it('h264 恒可直放，不触发探测', () => {
    expect(canNativeDirectPlay('mp4', 'h264')).toBe(true)
    expect(canPlayTypeSpy).not.toHaveBeenCalled()
  })

  it('探测不通过（canPlayType 返回空串）：hevc+mov 判为不可直放', () => {
    canPlayTypeSpy.mockReturnValue('')
    expect(canNativeDirectPlay('mov', 'hevc')).toBe(false)
  })

  it('探测通过（canPlayType 返回 maybe）：hevc+mp4 判为可直放', () => {
    canPlayTypeSpy.mockReturnValue('maybe')
    expect(canNativeDirectPlay('mp4', 'hevc')).toBe(true)
    expect(canPlayTypeSpy).toHaveBeenCalledWith('video/mp4; codecs="hvc1.1.6.L120.90"')
  })

  it('未收录的容器/编码保守放行，不触发探测', () => {
    expect(canNativeDirectPlay('mkv', 'hevc')).toBe(true)
    expect(canNativeDirectPlay('mp4', 'mpeg4')).toBe(true)
    expect(canPlayTypeSpy).not.toHaveBeenCalled()
  })

  it('探测结果按会话缓存：同一 容器+编码 组合不重复探测', () => {
    canPlayTypeSpy.mockReturnValue('maybe')
    expect(canNativeDirectPlay('webm', 'hevc')).toBe(true)
    expect(canNativeDirectPlay('webm', 'hevc')).toBe(true)
    expect(canPlayTypeSpy).toHaveBeenCalledTimes(1)
  })
})