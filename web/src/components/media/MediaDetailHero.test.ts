import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import MediaDetailHero from './MediaDetailHero.vue'

function mountHero(
  showRefresh = true,
  extraProps: Record<string, unknown> = {},
  slots: Record<string, string> = {},
) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }],
  })
  return mount(MediaDetailHero, {
    props: { title: '钢铁侠', showRefresh, ...extraProps },
    attachTo: document.body,
    global: { plugins: [router] },
    slots,
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

describe('MediaDetailHero 桌面端 1:2 两栏布局（工单 01：左海报 + 右内容 + 插槽）', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  /**
   * 定位主体两栏容器：h1 固定渲染于右栏（标题行内），其祖父节点即右栏容器，
   * 右栏的父节点为主体容器（两栏），第一子元素为左栏。
   * 用 DOM 结构/文本关系断言，不断言 Tailwind 类名字符串。
   */
  function layoutColumns(wrapper: ReturnType<typeof mountHero>) {
    const h1 = wrapper.get('h1').element
    const rightColumn = h1.parentElement!.parentElement!
    const body = rightColumn.parentElement!
    expect(body.children).toHaveLength(2)
    return { leftColumn: body.children[0], rightColumn }
  }

  it('桌面端左栏仅含海报，右栏包含标题/操作按钮/简介', () => {
    const wrapper = mountHero(true, { posterUrl: '/poster.jpg', overview: '一段简介' })
    const { leftColumn, rightColumn } = layoutColumns(wrapper)
    // 左栏：仅海报 img，无其他内容
    expect(leftColumn.querySelector('img')).not.toBeNull()
    expect(leftColumn.textContent!.trim()).toBe('')
    // 右栏：标题 / 元信息下方操作按钮组 / 简介
    expect(rightColumn.querySelector('h1')!.textContent).toContain('钢铁侠')
    expect(rightColumn.querySelector('button[title="修正匹配"]')).not.toBeNull()
    expect(Array.from(rightColumn.querySelectorAll('button')).some((b) => b.textContent!.includes('播放'))).toBe(true)
    expect(rightColumn.textContent).toContain('一段简介')
    wrapper.unmount()
  })

  it('左栏无海报图时渲染占位图标，仍无其他内容', () => {
    const wrapper = mountHero(true, { overview: '一段简介' })
    const { leftColumn } = layoutColumns(wrapper)
    expect(leftColumn.querySelector('img')).toBeNull()
    expect(leftColumn.querySelector('svg')).not.toBeNull() // Film 占位图标
    expect(leftColumn.textContent!.trim()).toBe('')
    wrapper.unmount()
  })

  it('传入默认插槽时渲染在简介之后（右栏内），未传入时不渲染', () => {
    const withSlot = mountHero(
      true,
      { posterUrl: '/poster.jpg', overview: '一段简介' },
      { default: '<p data-slot="page-content">页面级插槽内容</p>' },
    )
    const withRight = layoutColumns(withSlot).rightColumn
    const slotEl = withRight.querySelector('[data-slot="page-content"]')!
    expect(slotEl).not.toBeNull()
    // 插槽位于简介之后，且是右栏最后一个子元素（无其他内容跟在后面）
    expect(withRight.textContent!.indexOf('一段简介')).toBeLessThan(withRight.textContent!.indexOf('页面级插槽内容'))
    expect(withRight.lastElementChild!.querySelector('[data-slot="page-content"]')).not.toBeNull()
    withSlot.unmount()

    const without = mountHero(true, { posterUrl: '/poster.jpg', overview: '一段简介' })
    const withoutRight = layoutColumns(without).rightColumn
    expect(withoutRight.querySelector('[data-slot="page-content"]')).toBeNull()
    // 未传插槽时右栏最后一个子元素即简介 <p>，无多余容器/间距
    expect(withoutRight.lastElementChild!.textContent).toContain('一段简介')
    without.unmount()
  })
})
