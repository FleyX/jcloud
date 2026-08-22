import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import MovieDetail from './MovieDetail.vue'
import type { MediaItemDetailVo, MediaMovieVersionVo } from '@/types/media'

const { fetchItemDetail, refreshMetadata, toggleFavorite, updateMediaMatch, updateMediaWatched } =
  vi.hoisted(() => ({
    fetchItemDetail: vi.fn(),
    refreshMetadata: vi.fn(),
    toggleFavorite: vi.fn(),
    updateMediaMatch: vi.fn(),
    updateMediaWatched: vi.fn(),
  }))

vi.mock('@/api/media', () => ({
  fetchItemDetail,
  refreshMetadata,
  toggleFavorite,
  updateMediaMatch,
  updateMediaWatched,
}))

function buildVersion(overrides: Partial<MediaMovieVersionVo> = {}): MediaMovieVersionVo {
  return {
    id: 'v1',
    fileNodeId: 'fn1',
    fileName: 'movie-1080p.mkv',
    fileSize: '1073741824',
    durationMs: '6660000',
    container: 'mkv',
    videoCodec: 'hevc',
    audioCodec: 'aac',
    width: 1920,
    height: 1080,
    ...overrides,
  }
}

function buildDetail(overrides: Partial<MediaItemDetailVo> = {}): MediaItemDetailVo {
  return {
    id: 'm1',
    itemType: 'movie',
    fileName: 'movie-1080p.mkv',
    fileSize: 1073741824,
    matchStatus: 'matched',
    metadataId: 'md1',
    seriesId: null,
    seriesName: null,
    seasonNo: null,
    episodeNo: null,
    durationMs: 6660000,
    watched: false,
    progressMs: 0,
    width: 1920,
    height: 1080,
    videoCodec: 'hevc',
    audioCodec: 'aac',
    title: '走到尽头',
    originalTitle: null,
    overview: null,
    genres: [],
    releaseDate: '2014-05-01',
    voteAverage: 7.1,
    posterUrl: null,
    backdropUrl: null,
    metadataComplete: true,
    defaultVersionId: 'v1',
    versions: null,
    favorited: false,
    ...overrides,
  }
}

async function mountDetail(detail: MediaItemDetailVo) {
  fetchItemDetail.mockResolvedValue(detail)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/movie/:id', component: { template: '<div />' } }],
  })
  router.push('/movie/m1')
  await router.isReady()
  const wrapper = mount(MovieDetail, {
    attachTo: document.body,
    global: { plugins: [router, createPinia()] },
  })
  await flushPromises()
  return wrapper
}

describe('MovieDetail 源文件名移除（Jellyfin 改版）', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
  })

  it('单版本时不展示源文件名', async () => {
    const wrapper = await mountDetail(buildDetail({ versions: [buildVersion()] }))
    expect(wrapper.text()).not.toContain('movie-1080p.mkv')
    wrapper.unmount()
  })

  it('多版本列表主标题为分辨率，不展示文件名', async () => {
    const wrapper = await mountDetail(
      buildDetail({
        versions: [
          buildVersion(),
          buildVersion({ id: 'v2', fileNodeId: 'fn2', fileName: 'movie-4k.mkv', width: 3840, height: 2160 }),
        ],
      }),
    )
    expect(wrapper.text()).toContain('1920×1080')
    expect(wrapper.text()).toContain('3840×2160')
    expect(wrapper.text()).not.toContain('movie-1080p.mkv')
    expect(wrapper.text()).not.toContain('movie-4k.mkv')
    wrapper.unmount()
  })

  it('无分辨率时主标题回退容器格式，皆无回退「版本 N」', async () => {
    const wrapper = await mountDetail(
      buildDetail({
        versions: [
          buildVersion({ width: null, height: null, container: 'mp4' }),
          buildVersion({ id: 'v2', fileNodeId: 'fn2', width: null, height: null, container: null }),
        ],
      }),
    )
    expect(wrapper.text()).toContain('MP4')
    expect(wrapper.text()).toContain('版本 2')
    wrapper.unmount()
  })
})

/**
 * 定位 Hero 右栏：h1 固定渲染于右栏（标题行内），其祖父节点即右栏容器（复用 MediaDetailHero 测试思路）。
 * 返回右栏元素用于断言版本列表渲染在插槽内，不断言 Tailwind 类名。
 */
function rightColumn(wrapper: Awaited<ReturnType<typeof mountDetail>>): HTMLElement {
  const h1 = wrapper.get('h1').element
  return h1.parentElement!.parentElement!
}

describe('MovieDetail 多版本列表迁入右栏（工单 03）', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
  })

  it('多版本时「版本（N）」标题与版本行渲染在右栏内、简介之后', async () => {
    const wrapper = await mountDetail(
      buildDetail({
        overview: '一段简介',
        versions: [
          buildVersion(),
          buildVersion({ id: 'v2', fileNodeId: 'fn2', width: 3840, height: 2160 }),
        ],
      }),
    )
    const right = rightColumn(wrapper)
    expect(right.textContent).toContain('一段简介')
    // 标题与版本行在右栏内、简介之后（简介在前、版本列表在后）
    expect(right.textContent).toContain('版本（2）')
    expect(right.textContent).toContain('1920×1080')
    expect(right.textContent).toContain('3840×2160')
    expect(right.textContent!.indexOf('一段简介')).toBeLessThan(right.textContent!.indexOf('版本（2）'))
    wrapper.unmount()
  })

  it('单版本时不渲染版本列表', async () => {
    const wrapper = await mountDetail(buildDetail({ versions: [buildVersion()] }))
    const right = rightColumn(wrapper)
    expect(right.textContent).not.toContain('版本（1）')
    expect(right.textContent).not.toContain('1920×1080')
    wrapper.unmount()
  })
})
