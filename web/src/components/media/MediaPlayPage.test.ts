import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import MediaPlayPage from './MediaPlayPage.vue'
import type { MediaItemDetailVo } from '@/types/media'

const mocks = vi.hoisted(() => ({
  fetchItemDetail: vi.fn(),
  fetchMediaEpisodes: vi.fn(),
  useMediaPlayback: vi.fn(),
  usePlayerControls: vi.fn(),
}))

vi.mock('@/api/media', () => ({
  fetchItemDetail: mocks.fetchItemDetail,
  fetchMediaEpisodes: mocks.fetchMediaEpisodes,
}))

vi.mock('@/composables/useMediaPlayback', () => ({
  useMediaPlayback: mocks.useMediaPlayback,
}))

vi.mock('@/composables/usePlayerControls', () => ({
  usePlayerControls: mocks.usePlayerControls,
}))

function buildPlaybackMock(overrides: { playbackInfo?: unknown } = {}) {
  return {
    loading: ref(false),
    errorMsg: ref(''),
    playbackInfo: ref(overrides.playbackInfo ?? null),
    currentVersionId: ref(null),
    audioIndex: ref(null),
    subtitleKey: ref(null),
    bitrateTierKey: ref('original'),
    activeSubtitle: ref(null),
    sourceEpoch: ref(0),
    transcodeActive: ref(false),
    transcodeBaseMs: ref(0),
    start: vi.fn().mockResolvedValue(undefined),
    stop: vi.fn(),
    handleSeeking: vi.fn(),
    seekToAbsolute: vi.fn().mockResolvedValue(undefined),
    reportProgress: vi.fn(),
    handleTrackLoad: vi.fn(),
    selectAudioTrack: vi.fn(),
    selectSubtitle: vi.fn(),
    selectBitrateTier: vi.fn(),
    selectVersion: vi.fn(),
  }
}

function buildDetail(): MediaItemDetailVo {
  return {
    id: 'movie-1',
    fileNodeId: 'file-1',
    itemType: 'movie',
    title: '测试电影',
  } as unknown as MediaItemDetailVo
}

async function mountPlayPage() {
  const controlsVisible = ref(false)
  const wake = vi.fn(() => {
    controlsVisible.value = true
  })
  const toggleControls = vi.fn(() => {
    if (controlsVisible.value) {
      controlsVisible.value = false
    } else {
      wake()
    }
  })

  mocks.fetchItemDetail.mockResolvedValue(buildDetail())
  mocks.fetchMediaEpisodes.mockResolvedValue([])
  mocks.useMediaPlayback.mockReturnValue(buildPlaybackMock())
  mocks.usePlayerControls.mockReturnValue({ controlsVisible, wake, toggleControls })

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/media/play/:id', name: 'MediaPlay', component: MediaPlayPage }],
  })
  await router.push('/media/play/movie-1')
  await router.isReady()
  const wrapper = mount(MediaPlayPage, {
    global: {
      plugins: [router],
      stubs: { PlayerControlBar: true },
    },
  })
  await flushPromises()
  return { wrapper, controlsVisible, wake, toggleControls }
}

describe('MediaPlayPage 任意区域唤醒控制栏', () => {
  it('触摸后合成 click 不应把已唤醒的控制栏立即隐藏', async () => {
    const { wrapper, controlsVisible, wake, toggleControls } = await mountPlayPage()
    const video = wrapper.get('video')

    await video.trigger('touchstart')
    await video.trigger('click')

    expect(wake).toHaveBeenCalled()
    expect(toggleControls).not.toHaveBeenCalled()
    expect(controlsVisible.value).toBe(true)
  })
})

describe('MediaPlayPage 纯播放模式', () => {
  it('纯播放路由：不拉详情/选集，以文件节点开播，标题显示 fileName', async () => {
    vi.clearAllMocks()
    const playbackMock = buildPlaybackMock({ playbackInfo: { fileName: 'home-video.mp4' } })
    mocks.useMediaPlayback.mockReturnValue(playbackMock)
    mocks.usePlayerControls.mockReturnValue({
      controlsVisible: ref(false),
      wake: vi.fn(),
      toggleControls: vi.fn(),
    })

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/media/play/file/:fileNodeId', name: 'MediaPlayFile', component: MediaPlayPage }],
    })
    await router.push('/media/play/file/fn-1')
    await router.isReady()
    const wrapper = mount(MediaPlayPage, {
      global: {
        plugins: [router],
        stubs: { PlayerControlBar: true },
      },
    })
    await flushPromises()

    // 纯播放对媒体数据零写入：不发起详情/选集请求
    expect(mocks.fetchItemDetail).not.toHaveBeenCalled()
    expect(mocks.fetchMediaEpisodes).not.toHaveBeenCalled()
    expect(playbackMock.start).toHaveBeenCalledWith('fn-1', undefined, undefined, { pure: true })
    // 标题栏显示文件名
    expect(wrapper.text()).toContain('home-video.mp4')
    // 控制栏不展示选集/版本入口
    const controlBar = wrapper.findComponent({ name: 'PlayerControlBar' })
    expect(controlBar.props('isEpisode')).toBe(false)
    expect(controlBar.props('versions')).toEqual([])
  })
})
