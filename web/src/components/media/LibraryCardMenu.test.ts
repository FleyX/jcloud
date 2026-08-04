import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import LibraryCardMenu, { type LibraryMenuAction } from './LibraryCardMenu.vue'
import type { MediaType } from '@/types/media'

function mountMenu(mediaType: MediaType) {
  return mount(LibraryCardMenu, {
    props: { mediaType },
    attachTo: document.body,
  })
}

/** 点击触发按钮打开菜单，返回 wrapper 用于断言 action emit */
async function openMenu(mediaType: MediaType) {
  const wrapper = mountMenu(mediaType)
  const trigger = document.querySelector('button[title="库操作"]') as HTMLButtonElement
  trigger.click()
  await flushPromises()
  return wrapper
}

/** 菜单内容经 DropdownMenuPortal 渲染到 body，按 role=menuitem 查找 */
function menuItems(): HTMLElement[] {
  return Array.from(document.querySelectorAll('[role="menuitem"]'))
}

describe('LibraryCardMenu', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('点击触发按钮可打开菜单（内容渲染在卡片裁剪上下文之外的 body）', async () => {
    const wrapper = await openMenu('movie')
    expect(menuItems().length).toBeGreaterThan(0)
    expect(document.body.textContent).toContain('扫描媒体库')
    expect(wrapper.html()).not.toContain('扫描媒体库')
    wrapper.unmount()
  })

  it.each(['movie', 'tv'] as const)('电影/剧集库（%s）显示三个菜单项', async (mediaType) => {
    await openMenu(mediaType)
    expect(menuItems().map((i) => i.textContent!.trim())).toEqual([
      '扫描媒体库',
      '刷新缺失元数据',
      '强制刷新所有元数据',
    ])
  })

  it('其他类型库只显示扫描媒体库一项', async () => {
    const wrapper = await openMenu('other')
    expect(menuItems().map((i) => i.textContent!.trim())).toEqual(['扫描媒体库'])
    wrapper.unmount()
  })

  it.each([
    ['扫描媒体库', 'scan'],
    ['刷新缺失元数据', 'refresh-missing'],
    ['强制刷新所有元数据', 'refresh-all'],
  ] as const)('点击「%s」emit %s 动作并关闭菜单', async (label, kind) => {
    const wrapper = await openMenu('movie')
    const item = menuItems().find((i) => i.textContent!.includes(label))!
    item.click()
    await flushPromises()
    expect(wrapper.emitted('action')).toEqual([[kind as LibraryMenuAction]])
    expect(menuItems()).toHaveLength(0)
    wrapper.unmount()
  })
})
