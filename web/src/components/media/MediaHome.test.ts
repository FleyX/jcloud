import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Router } from 'vue-router'
import MediaHome from './MediaHome.vue'
import type { MediaHomeVo, MediaItemVo } from '@/types/media'

const { fetchMediaHome } = vi.hoisted(() => ({
  fetchMediaHome: vi.fn(),
}))

vi.mock('@/api/media', () => ({
  fetchMediaHome,
  scanMediaDirectory: vi.fn(),
  scrapeMediaDirectory: vi.fn(),
  withToken: (url: string) => url,
}))

function buildItem(overrides: Partial<MediaItemVo> = {}): MediaItemVo {
  return {
    id: '1',
    fileNodeId: null,
    itemType: 'movie',
    fileName: 'movie.mp4',
    matchStatus: 'matched',
    metadataId: 'md1',
    seriesId: null,
    seriesName: null,
    title: 'Movie 1',
    posterUrl: null,
    releaseDate: null,
    voteAverage: null,
    durationMs: null,
    seasonNo: null,
    episodeNo: null,
    progressMs: 0,
    lastPlayTime: null,
    addedTime: '2023-03-04 10:00:00',
    metadataComplete: true,
    favorited: false,
    ...overrides,
  }
}

function buildHome(overrides: Partial<MediaHomeVo> = {}): MediaHomeVo {
  return {
    libraries: [],
    continueWatching: [],
    nextUp: [],
    latestMovies: [],
    latestSeries: [],
    ...overrides,
  }
}

let router: Router

async function createRouterWithRoutes() {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/media', name: 'MediaHome', component: { template: '<div />' } },
      { path: '/media/libraries/:id', name: 'MediaLibrary', component: { template: '<div />' } },
      { path: '/media/directories', name: 'MediaDirectories', component: { template: '<div />' } },
      { path: '/media/movies/:id', name: 'MediaMovieDetail', component: { template: '<div />' } },
      { path: '/media/series/:id', name: 'MediaSeriesDetail', component: { template: '<div />' } },
      { path: '/media/play/:id', name: 'MediaPlay', component: { template: '<div />' } },
    ],
  })
  await router.push('/media')
  await router.isReady()
}

async function mountHome(home: MediaHomeVo) {
  fetchMediaHome.mockResolvedValue(home)
  const wrapper = mount(MediaHome, {
    global: {
      plugins: [createPinia(), router],
      stubs: {
        MediaTopMenu: true,
        MediaFavorites: true,
        GlobalSearchModal: true,
        LibraryCardMenu: true,
      },
    },
  })
  await flushPromises()
  return wrapper
}

describe('MediaHome latest sections', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it('shows the full-page empty state only when all five sections are empty', async () => {
    await createRouterWithRoutes()
    const wrapper = await mountHome(buildHome())
    expect(wrapper.text()).toContain('还没有媒体库')
    expect(wrapper.findAll('section')).toHaveLength(0)
  })

  it('hides the full-page empty state when only latestMovies has content', async () => {
    await createRouterWithRoutes()
    const wrapper = await mountHome(buildHome({ latestMovies: [buildItem()] }))
    expect(wrapper.text()).not.toContain('还没有媒体库')

    const headings = wrapper.findAll('section h2').map((h) => h.text())
    expect(headings).toEqual(['最新电影'])
    expect(headings).not.toContain('我的媒体')
    expect(headings).not.toContain('最新剧集')
  })

  it('renders sections in the exact order libraries, continueWatching, nextUp, latestMovies, latestSeries', async () => {
    await createRouterWithRoutes()
    const home = buildHome({
      libraries: [
        { id: 'lib1', name: 'My Library', mediaType: 'movie', itemCount: 3 } as unknown as MediaHomeVo['libraries'][number],
      ],
      continueWatching: [buildItem({ id: 'cw1' })],
      nextUp: [buildItem({ id: 'nu1', itemType: 'episode', seriesId: 'sr1' })],
      latestMovies: [buildItem({ id: 'lm1' })],
      latestSeries: [buildItem({ id: 'ls1', itemType: 'series', title: 'Series 1' })],
    })
    const wrapper = await mountHome(home)
    expect(wrapper.findAll('section h2').map((h) => h.text())).toEqual([
      '我的媒体',
      '继续观看',
      '接下来',
      '最新电影',
      '最新剧集',
    ])
  })

  it('routes a latest movie card to the movie detail page, not the player', async () => {
    await createRouterWithRoutes()
    const wrapper = await mountHome(buildHome({ latestMovies: [buildItem({ id: 'mv1' })] }))

    const movieSection = wrapper.findAll('section').find((s) => s.find('h2').text() === '最新电影')!
    await movieSection.find('.group').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('MediaMovieDetail')
    expect(router.currentRoute.value.params.id).toBe('mv1')
  })

  it('routes a latest series card to the series detail page, not the player', async () => {
    await createRouterWithRoutes()
    const wrapper = await mountHome(
      buildHome({
        latestSeries: [buildItem({ id: 'sr1', itemType: 'series', title: 'Series 1' })],
      }),
    )

    const seriesSection = wrapper.findAll('section').find((s) => s.find('h2').text() === '最新剧集')!
    await seriesSection.find('.group').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('MediaSeriesDetail')
    expect(router.currentRoute.value.params.id).toBe('sr1')
  })

  it('shows the relative added time on latest cards', async () => {
    await createRouterWithRoutes()
    // 30 秒前入库，应显示「刚刚」
    const d = new Date(Date.now() - 30_000 + 8 * 3_600_000)
    const p = (n: number) => String(n).padStart(2, '0')
    const addedTime = `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())} ${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())}`
    const wrapper = await mountHome(
      buildHome({
        latestMovies: [buildItem({ id: 'mv1', addedTime })],
      }),
    )
    const movieSection = wrapper.findAll('section').find((s) => s.find('h2').text() === '最新电影')!
    expect(movieSection.text()).toContain('Movie 1')
    expect(movieSection.text()).toContain('刚刚')
  })
})
