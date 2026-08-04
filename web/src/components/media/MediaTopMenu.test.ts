import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import MediaTopMenu from './MediaTopMenu.vue'

const tabs = [
  { key: 'library', label: '电影' },
  { key: 'favorites', label: '我的收藏' },
  { key: 'genres', label: '类型' },
]

function mountMenu(active = 'library') {
  return mount(MediaTopMenu, {
    props: { tabs, active },
    slots: {
      default: '<button type="button" class="search-action">搜索</button>',
    },
  })
}

describe('MediaTopMenu 布局语义', () => {
  it('移动端 Tab 靠左、单行不换行且超出宽度可横向滚动', () => {
    const wrapper = mountMenu()
    const tabBar = wrapper.get('.overflow-x-auto')
    const classes = tabBar.classes()
    expect(classes).toContain('justify-start')
    expect(classes).toContain('overflow-x-auto')
    expect(classes).toContain('whitespace-nowrap')
    expect(wrapper.get('.overflow-x-auto > div').classes()).toContain('w-max')
    expect(wrapper.get('.overflow-x-auto > div').classes()).toContain('shrink-0')
  })

  it('PC 端 Tab 以整行宽度为基准居中', () => {
    const wrapper = mountMenu()
    const tabBar = wrapper.get('.overflow-x-auto')
    expect(tabBar.classes()).toContain('md:justify-center')
  })

  it('操作插槽移动端参与布局占位且不收缩，PC 端脱离 Tab 流固定右置', () => {
    const wrapper = mountMenu()
    const slot = wrapper.get('.search-action').element.parentElement
    expect(slot).not.toBeNull()
    expect(slot!.classList.contains('shrink-0')).toBe(true)
    expect(slot!.classList.contains('md:absolute')).toBe(true)
    expect(slot!.classList.contains('md:inset-y-0')).toBe(true)
    expect(slot!.classList.contains('md:right-4')).toBe(true)
  })

  it('点击 Tab 触发 select 事件并携带对应 key', async () => {
    const wrapper = mountMenu()
    const favorites = wrapper.findAll('button').find((b) => b.text() === '我的收藏')!
    await favorites.trigger('click')
    expect(wrapper.emitted('select')).toEqual([['favorites']])
  })

  it('激活 Tab 高亮并渲染下划线指示器，非激活 Tab 不渲染', () => {
    const wrapper = mountMenu('favorites')
    const favorites = wrapper.findAll('button').find((b) => b.text() === '我的收藏')!
    const library = wrapper.findAll('button').find((b) => b.text() === '电影')!
    expect(favorites.classes()).toContain('font-semibold')
    expect(favorites.classes()).toContain('text-primary-600')
    expect(library.classes()).toContain('text-surface-500')
    // 仅激活 Tab 带下划线指示器
    expect(wrapper.findAll('span').filter((s) => s.classes().includes('bg-primary-500'))).toHaveLength(1)
  })
})
