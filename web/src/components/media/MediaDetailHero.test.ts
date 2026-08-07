import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import MediaDetailHero from './MediaDetailHero.vue'

function mountHero(showRefresh = true) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }],
  })
  return mount(MediaDetailHero, {
    props: { title: '钢铁侠', showRefresh },
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
