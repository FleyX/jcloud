import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import PcAppShell from './PcAppShell.vue'

async function mountShell(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/media', name: 'MediaHome', component: { template: '<div />' } },
      { path: '/media/libraries/:id', name: 'MediaLibrary', component: { template: '<div />' } },
      { path: '/media/movies/:id', name: 'MediaMovieDetail', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(PcAppShell, {
    global: {
      plugins: [router],
      stubs: {
        Header: true,
        Sidebar: true,
        TransferPanel: true,
        TransferTaskPanel: true,
      },
    },
  })
  await flushPromises()
  return wrapper
}

describe('PcAppShell 影视页面顶部间距', () => {
  it('removes top padding on pages with the media top menu', async () => {
    const home = await mountShell('/media')
    const library = await mountShell('/media/libraries/lib-1')

    expect(home.get('main').classes()).toEqual(expect.arrayContaining(['px-6', 'pb-6']))
    expect(home.get('main').classes()).not.toContain('p-6')
    expect(library.get('main').classes()).toEqual(expect.arrayContaining(['px-6', 'pb-6']))
  })

  it('keeps the standard padding on media detail pages', async () => {
    const detail = await mountShell('/media/movies/movie-1')

    expect(detail.get('main').classes()).toContain('p-6')
  })
})
