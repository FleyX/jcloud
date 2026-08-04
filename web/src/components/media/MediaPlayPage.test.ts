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

function buildPlaybackMock() {
  return {
    loading: ref(false),
    errorMsg: ref(''),
    playbackInfo: ref(null),
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
