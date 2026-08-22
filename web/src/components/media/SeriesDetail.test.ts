import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import SeriesDetail from './SeriesDetail.vue'
import type { MediaItemVo, MediaSeriesDetailVo, MediaSeriesSeasonVo } from '@/types/media'

const { fetchSeasonEpisodes, fetchSeriesDetail, refreshMetadata, toggleFavorite, updateMediaMatch, updateMediaWatched } =
  vi.hoisted(() => ({
    fetchSeasonEpisodes: vi.fn(),
    fetchSeriesDetail: vi.fn(),
    refreshMetadata: vi.fn(),
    toggleFavorite: vi.fn(),
    updateMediaMatch: vi.fn(),
    updateMediaWatched: vi.fn(),
  }))

vi.mock('@/api/media', () => ({
  fetchSeasonEpisodes,
  fetchSeriesDetail,
  refreshMetadata,
  toggleFavorite,
  updateMediaMatch,
  updateMediaWatched,
}))

function buildSeason(overrides: Partial<MediaSeriesSeasonVo> = {}): MediaSeriesSeasonVo {
  return {
    seasonId: 's1',
    seasonNo: 1,
    posterUrl: null,
    episodeCount: 2,
    hasProgress: false,
    watched: false,
    favorited: false,
    ...overrides,
  }
}

function buildEpisode(overrides: Partial<MediaItemVo> = {}): MediaItemVo {
  return {
    id: 'e1',
    fileNodeId: 'fn1',
    itemType: 'episode',
    fileName: null,
    matchStatus: 'matched',
    metadataId: 'md1',
    seriesId: 'ser1',
    seriesName: '测试剧',
    title: '测试剧 S01E01',
    posterUrl: null,
    releaseDate: null,
    voteAverage: null,
    durationMs: 2400000,
    seasonNo: 1,
    episodeNo: 1,
    progressMs: null,
    watched: false,
    lastPlayTime: null,
    addedTime: null,
    metadataComplete: true,
    favorited: false,
    ...overrides,
  }
}

function buildDetail(overrides: Partial<MediaSeriesDetailVo> = {}): MediaSeriesDetailVo {
  return {
    seriesName: '测试剧',
    matchStatus: 'matched',
    metadataId: 'md1',
    title: '测试剧',
    originalTitle: null,
    overview: '一段简介',
    genres: [],
    releaseDate: '2020-01-01',
    voteAverage: 8.0,
    seasonCount: 1,
    posterUrl: null,
    backdropUrl: null,
    seasons: [buildSeason()],
    metadataComplete: true,
    watched: false,
    favorited: false,
    ...overrides,
  }
}

async function mountDetail(detail: MediaSeriesDetailVo, query: Record<string, string> = {}) {
  fetchSeriesDetail.mockResolvedValue(detail)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/series/:id', component: { template: '<div />' } }],
  })
  await router.push({ path: '/series/ser1', query })
  await router.isReady()
  const wrapper = mount(SeriesDetail, {
    attachTo: document.body,
    global: { plugins: [router, createPinia()] },
  })
  await flushPromises()
  return wrapper
}

/**
 * 定位 Hero 右栏：h1 固定渲染于右栏（标题行内），其祖父节点即右栏容器（复用 MediaDetailHero 测试思路）。
 * 返回右栏元素用于断言季网格/剧集列表渲染在插槽内，不断言 Tailwind 类名。
 */
function rightColumn(wrapper: Awaited<ReturnType<typeof mountDetail>>): HTMLElement {
  const h1 = wrapper.get('h1').element
  return h1.parentElement!.parentElement!
}

describe('SeriesDetail 季网格/剧集列表迁入右栏（工单 02）', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
  })

  it('季网格态：季标题与季卡片渲染在右栏内简介之后', async () => {
    const wrapper = await mountDetail(buildDetail({ overview: '一段简介' }))
    const right = rightColumn(wrapper)
    expect(right.textContent).toContain('一段简介')
    // 季标题与季卡片在右栏内
    expect(right.textContent).toContain('季（1）')
    expect(right.textContent).toContain('第 1 季')
    expect(right.textContent).toContain('共 2 集')
    // 位于简介之后（简介在前、季网格在后）
    expect(right.textContent!.indexOf('一段简介')).toBeLessThan(right.textContent!.indexOf('季（1）'))
    wrapper.unmount()
  })

  it('季剧集态：返回季列表按钮与剧集列表渲染在右栏内', async () => {
    fetchSeasonEpisodes.mockResolvedValue([buildEpisode()])
    const wrapper = await mountDetail(buildDetail(), { season: 's1' })
    const right = rightColumn(wrapper)
    // 返回季列表按钮与集标题在右栏内
    const backButton = Array.from(right.querySelectorAll('button')).find((b) => b.textContent!.includes('返回季列表'))
    expect(backButton).not.toBeNull()
    expect(right.textContent).toContain('第 1 季（2 集）')
    expect(right.textContent).toContain('测试剧 S01E01')
    // 季网格态不再渲染
    expect(right.textContent).not.toContain('季（1）')
    // 懒加载经当前季后端接口
    expect(fetchSeasonEpisodes).toHaveBeenCalledWith('ser1', 's1')
    wrapper.unmount()
  })
})