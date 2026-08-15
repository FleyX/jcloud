import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, nextTick, ref, watch, type Ref } from 'vue'
import PlayerControlBar from './PlayerControlBar.vue'
import type { PlayerControls } from '@/composables/usePlayerControls'
import type { MediaPlaybackInfoVo } from '@/types/media'

/**
 * 可写的 controls 模拟类型：真实 PlayerControls 中 duration/currentTime/bufferedEnd 为只读
 * computed，测试中改用 ref 以便注入播放进度
 */
interface FakeControls {
  playing: Ref<boolean>
  currentTime: Ref<number>
  duration: Ref<number>
  bufferedEnd: Ref<number>
  volume: Ref<number>
  muted: Ref<boolean>
  playbackRate: Ref<number>
  isFullscreen: Ref<boolean>
  pipSupported: boolean
  rateMenuOpen: Ref<boolean>
  subtitleMenuOpen: Ref<boolean>
  bitrateMenuOpen: Ref<boolean>
  audioMenuOpen: Ref<boolean>
  versionMenuOpen: Ref<boolean>
  controlsVisible: Ref<boolean>
  wake: () => void
  toggleControls: () => void
  togglePlay: () => void
  seekTo: (seconds: number) => void
  seekBy: (delta: number) => void
  setVolume: (value: number) => void
  toggleMute: () => void
  setRate: (rate: number) => void
  toggleFullscreen: () => void
  togglePip: () => void
}

/**
 * PlayerControls 公开契约模拟（作为 prop 传入，测试不依赖真实 video 或浏览器布局）：
 * - wake 复刻真实语义：显示控制栏；播放中启动 3s 隐藏倒计时，暂停/停止时不启动
 * - playing 变化时同步触发 wake，与 usePlayerControls 内 watch([playing, ...], wake) 一致
 */
function createFakeControls(): FakeControls {
  const playing = ref(false)
  const controlsVisible = ref(true)
  let hideTimer: ReturnType<typeof setTimeout> | null = null
  const wake = () => {
    controlsVisible.value = true
    if (hideTimer) clearTimeout(hideTimer)
    hideTimer = null
    if (playing.value) {
      hideTimer = setTimeout(() => {
        controlsVisible.value = false
      }, 3000)
    }
  }
  watch(playing, wake, { flush: 'sync' })
  return {
    playing,
    currentTime: ref(0),
    duration: ref(0),
    bufferedEnd: ref(0),
    volume: ref(1),
    muted: ref(false),
    playbackRate: ref(1),
    isFullscreen: ref(false),
    pipSupported: false,
    rateMenuOpen: ref(false),
    subtitleMenuOpen: ref(false),
    bitrateMenuOpen: ref(false),
    audioMenuOpen: ref(false),
    versionMenuOpen: ref(false),
    controlsVisible,
    wake,
    toggleControls: () => {},
    togglePlay: () => {},
    seekTo: () => {},
    seekBy: () => {},
    setVolume: () => {},
    toggleMute: () => {},
    setRate: () => {},
    toggleFullscreen: () => {},
    togglePip: () => {},
  }
}

function mountBar(controls: FakeControls): VueWrapper {
  return mount(PlayerControlBar, {
    props: {
      controls: controls as unknown as PlayerControls,
      playbackInfo: null,
      audioIndex: null,
      subtitleKey: null,
      bitrateTierKey: 'auto',
      isEpisode: false,
      episodePanelOpen: false,
    },
    global: { stubs: { PlayerOptionMenu: true } },
  })
}

/** 模拟固定进度条几何：宽 100px、左缘 0，pointerdown/move/up 的 clientX 即百分比秒数 */
const progressRect = {
  x: 0,
  y: 0,
  left: 0,
  top: 0,
  right: 100,
  bottom: 10,
  width: 100,
  height: 10,
  toJSON: () => ({}),
} as DOMRect

/** jsdom 下 PointerEvent 的 clientX 为只读 getter，VTU trigger 无法写入，改为原生构造 + dispatch */
function pointerEvent(element: Element, type: string, clientX: number) {
  element.dispatchEvent(new PointerEvent(type, { clientX, bubbles: true, cancelable: true }))
}

beforeEach(() => {
  // jsdom 未实现指针捕获，补一个 no-op 供组件调用
  Object.defineProperty(Element.prototype, 'setPointerCapture', { value: () => {}, configurable: true })
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(progressRect)
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('PlayerControlBar 播放器控制交互', () => {
  it('控制栏隐藏时，首次点击进度条即唤醒控制栏并完成 seek', async () => {
    vi.useFakeTimers()
    const controls = createFakeControls()
    controls.duration.value = 100
    controls.controlsVisible.value = false
    const wrapper = mountBar(controls)

    // 隐藏态：外层 pointer-events-none，但进度条保留指针命中能力，其余按钮不透明可点
    expect(wrapper.classes()).toContain('pointer-events-none')
    expect(wrapper.classes()).toContain('opacity-0')
    expect(wrapper.get('.touch-none').classes()).toContain('pointer-events-auto')
    expect(wrapper.get('button').classes()).not.toContain('pointer-events-auto')

    // 首击即唤醒并完成对应位置跳转
    const bar = wrapper.get('.touch-none').element
    pointerEvent(bar, 'pointerdown', 50)
    pointerEvent(bar, 'pointerup', 50)
    await nextTick()

    expect(controls.controlsVisible.value).toBe(true)
    expect(wrapper.classes()).toContain('opacity-100')
    expect(wrapper.emitted('seek')).toEqual([[50]])
  })

  it('拖拽期间控制栏持续显示，松手后播放中约 3 秒自动隐藏', async () => {
    vi.useFakeTimers()
    const controls = createFakeControls()
    controls.duration.value = 100
    controls.playing.value = true
    controls.controlsVisible.value = false
    const wrapper = mountBar(controls)
    const bar = wrapper.get('.touch-none').element

    // 首击开始拖拽：唤醒控制栏
    pointerEvent(bar, 'pointerdown', 20)
    await nextTick()
    expect(wrapper.classes()).toContain('opacity-100')

    // 拖拽中即使隐藏计时器到期也保持可见
    await vi.advanceTimersByTimeAsync(4000)
    await nextTick()
    expect(wrapper.classes()).toContain('opacity-100')
    expect(wrapper.classes()).not.toContain('pointer-events-none')

    // 拖拽移动刷新唤醒计时器，持续可见
    pointerEvent(bar, 'pointermove', 40)
    await vi.advanceTimersByTimeAsync(1000)
    await nextTick()
    expect(wrapper.classes()).toContain('opacity-100')

    // 松手 emit 绝对秒数 seek，并按播放状态重启隐藏倒计时
    pointerEvent(bar, 'pointerup', 60)
    await nextTick()
    expect(wrapper.emitted('seek')).toEqual([[60]])
    expect(wrapper.classes()).toContain('opacity-100')

    // 松手后约 3 秒无操作自动隐藏
    await vi.advanceTimersByTimeAsync(3000)
    await nextTick()
    expect(wrapper.classes()).toContain('pointer-events-none')
    expect(wrapper.classes()).toContain('opacity-0')
  })

  it('播放中无后续操作约 3 秒后控制栏自动隐藏', async () => {
    vi.useFakeTimers()
    const controls = createFakeControls()
    const wrapper = mountBar(controls)
    expect(wrapper.classes()).toContain('opacity-100')

    controls.playing.value = true // 触发 wake 启动 3s 隐藏倒计时
    await nextTick()
    await vi.advanceTimersByTimeAsync(2999)
    await nextTick()
    expect(wrapper.classes()).toContain('opacity-100')

    await vi.advanceTimersByTimeAsync(1)
    await nextTick()
    expect(wrapper.classes()).toContain('pointer-events-none')
  })

  it('暂停状态控制栏不自动隐藏', async () => {
    vi.useFakeTimers()
    const controls = createFakeControls()
    controls.playing.value = true // 播放中先启动倒计时
    const wrapper = mountBar(controls)
    await nextTick()

    // 播放中走一半后暂停：倒计时取消，控制栏常显
    await vi.advanceTimersByTimeAsync(1500)
    controls.playing.value = false
    await nextTick()
    await vi.advanceTimersByTimeAsync(5000)
    await nextTick()

    expect(wrapper.classes()).toContain('opacity-100')
    expect(wrapper.classes()).not.toContain('pointer-events-none')
  })
})

describe('PlayerControlBar 字幕菜单来源', () => {
  /** 渲染 options 的 PlayerOptionMenu 桩，便于断言字幕菜单项来源与徽标 */
  const MenuStub = defineComponent({
    props: { options: { type: Array, default: () => [] } },
    template: '<div class="stub-menu"><span v-for="o in options" :key="o.key">{{ o.label }}<i v-if="o.badge">{{ o.badge }}</i></span></div>',
  })

  function mountBarWithPlayback(playbackInfo: MediaPlaybackInfoVo): VueWrapper {
    const controls = createFakeControls()
    return mount(PlayerControlBar, {
      props: {
        controls: controls as unknown as PlayerControls,
        playbackInfo,
        audioIndex: null,
        subtitleKey: null,
        bitrateTierKey: 'auto',
        isEpisode: false,
        episodePanelOpen: false,
      },
      global: { stubs: { PlayerOptionMenu: MenuStub } },
    })
  }

  it('播放信息统一字幕列表不含 PGS 时，字幕菜单不展示 PGS 等不可用轨', () => {
    // 以后端播放信息为输入：subtitleTracks 可能残留原始 PGS 轨，但统一字幕列表已被过滤
    const playbackInfo = {
      mode: 'direct',
      directUrl: null,
      transcodeUrl: null,
      durationMs: 7_200_000,
      container: 'mkv',
      videoCodec: 'h264',
      audioCodec: 'aac',
      width: 1920,
      height: 1080,
      audioTracks: [{ index: 0, codec: 'aac', language: 'ja', title: null }],
      subtitleTracks: [
        { index: 0, codec: 'hdmv_pgs_subtitle', language: null, title: 'PGS' },
        { index: 1, codec: 'subrip', language: 'zh', title: null },
      ],
      subtitles: [
        { type: 'embedded', index: 1, subtitleId: null, label: '中文字幕', language: 'zh', defaulted: true, bitmap: false },
        { type: 'embedded', index: 0, subtitleId: null, label: '图形字幕', language: null, defaulted: false, bitmap: true },
      ],
      effectiveBitRate: null,
      progressMs: 0,
    } as unknown as MediaPlaybackInfoVo

    const wrapper = mountBarWithPlayback(playbackInfo)
    const menuLabels = wrapper.findAll('.stub-menu span').map((n) => n.text())

    expect(menuLabels).toContain('中文字幕')
    expect(menuLabels.some((label) => label.includes('PGS'))).toBe(false)
  })

  it('位图字幕项渲染「图形」徽标，文本项不渲染', () => {
    const playbackInfo = {
      mode: 'direct',
      directUrl: null,
      transcodeUrl: null,
      durationMs: 7_200_000,
      container: 'mkv',
      videoCodec: 'h264',
      audioCodec: 'aac',
      width: 1920,
      height: 1080,
      audioTracks: [{ index: 0, codec: 'aac', language: 'ja', title: null }],
      subtitleTracks: [],
      subtitles: [
        { type: 'external', index: null, subtitleId: 'srt-1', label: '中文字幕', language: 'zh', defaulted: false, bitmap: false },
        { type: 'embedded', index: 0, subtitleId: null, label: '图形字幕', language: null, defaulted: false, bitmap: true },
      ],
      effectiveBitRate: null,
      progressMs: 0,
    } as unknown as MediaPlaybackInfoVo

    const wrapper = mountBarWithPlayback(playbackInfo)
    const menuLabels = wrapper.findAll('.stub-menu span').map((n) => n.text())

    expect(menuLabels).toContain('中文字幕')
    expect(menuLabels).toContain('图形字幕图形')
    expect(menuLabels.filter((label) => label.includes('图形'))).toHaveLength(1)
  })
})
