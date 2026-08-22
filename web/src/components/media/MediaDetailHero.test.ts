import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import MediaDetailHero from './MediaDetailHero.vue'

function mountHero(showRefresh = true, extraProps: Record<string, unknown> = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }],
  })
  return mount(MediaDetailHero, {
    props: { title: '钢铁侠', showRefresh, ...extraProps },
    attachTo: document.body,
    global: { plugins: [router] },
  })
}

/** 点击刷新触发按钮打开菜单，返回 wrapper 用于断言 emit */
async function openRefreshMenu() {
  const wrapper = mountHero()
  const trigger = document.querySelector('button[title="刷新元数据"]') as HTMLButtonElement
  trigger.click()
  await flushPromises()
  return wrapper
}

/** 菜单内容经 DropdownMenuPortal 渲染到 body，按 role=menuitem 查找 */
function menuItems(): HTMLElement[] {
  return Array.from(document.querySelectorAll('[role="menuitem"]'))
}

describe('MediaDetailHero 刷新菜单（工单 06 两模式）', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('showRefresh 为真时显示刷新入口，展开后有两项（刷新缺失 / 强制刷新）', async () => {
    const wrapper = await openRefreshMenu()
    expect(menuItems().map((i) => i.textContent!.trim())).toEqual([
      '刷新缺失元数据',
      '强制刷新元数据',
    ])
    wrapper.unmount()
  })

  it.each([
    ['刷新缺失元数据', 'missing'],
    ['强制刷新元数据', 'force'],
  ] as const)('点击「%s」emit refresh(%s) 并关闭菜单', async (label, mode) => {
    const wrapper = await openRefreshMenu()
    const item = menuItems().find((i) => i.textContent!.includes(label))!
    item.click()
    await flushPromises()
    expect(wrapper.emitted('refresh')).toEqual([[mode]])
    expect(menuItems()).toHaveLength(0)
    wrapper.unmount()
  })

  it('showRefresh 为假时不显示刷新入口', () => {
    const wrapper = mountHero(false)
    expect(document.querySelector('button[title="刷新元数据"]')).toBeNull()
    wrapper.unmount()
  })
})

describe('MediaDetailHero 操作按钮（Jellyfin 改版：仅主播放键保留文字）', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('修正匹配/收藏/已观看/刷新仅图标且带悬浮提示，播放键保留文字', () => {
    const wrapper = mountHero()
    for (const title of ['修正匹配', '收藏', '标记已观看', '刷新元数据']) {
      const btn = document.querySelector(`button[title="${title}"]`)
      expect(btn, title).not.toBeNull()
      expect(btn!.textContent!.trim()).toBe('')
    }
    const play = wrapper.findAll('button').find((b) => b.text().includes('播放'))
    expect(play).toBeDefined()
    wrapper.unmount()
  })

  it('收藏/已观看状态下悬浮提示切换', () => {
    const wrapper = mountHero(true, { favorited: true, watched: true })
    expect(document.querySelector('button[title="取消收藏"]')).not.toBeNull()
    expect(document.querySelector('button[title="标记未观看"]')).not.toBeNull()
    wrapper.unmount()
  })

  it('有续播位置时播放键显示「继续播放」，并出现仅图标的从头播放按钮', () => {
    const wrapper = mountHero(true, { continueMs: 60000 })
    const play = wrapper.findAll('button').find((b) => b.text().includes('继续播放'))
    expect(play).toBeDefined()
    const restart = document.querySelector('button[title="从头播放"]')
    expect(restart).not.toBeNull()
    expect(restart!.textContent!.trim()).toBe('')
    wrapper.unmount()
  })
})
