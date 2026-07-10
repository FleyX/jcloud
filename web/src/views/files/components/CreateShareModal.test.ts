import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import CreateShareModal from './CreateShareModal.vue'

describe('CreateShareModal', () => {
  it('renders create form when open', () => {
    const wrapper = mount(CreateShareModal, {
      props: { open: true, itemIds: ['a', 'b'] },
    })
    expect(wrapper.text()).toContain('创建分享')
    expect(wrapper.text()).toContain('已选择 2 个项目')
  })

  it('emits close when clicking cancel', async () => {
    const wrapper = mount(CreateShareModal, {
      props: { open: true, itemIds: ['a'] },
    })
    const cancelButton = wrapper.findAll('button').find((w) => w.text() === '取消')
    expect(cancelButton).toBeDefined()
    await cancelButton!.trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('emits confirm payload when creating', async () => {
    const wrapper = mount(CreateShareModal, {
      props: { open: true, itemIds: ['a', 'b'] },
    })

    const nameInput = wrapper.find('input[type="text"]')
    await nameInput.setValue('测试分享')

    const confirmButton = wrapper.findAll('button').find((w) => w.text() === '创建')
    expect(confirmButton).toBeDefined()
    await confirmButton!.trigger('click')

    expect(wrapper.emitted('confirm')).toHaveLength(1)
    const payload = wrapper.emitted('confirm')![0][0] as Record<string, unknown>
    expect(payload.name).toBe('测试分享')
    expect(payload.fileNodeIds).toEqual(['a', 'b'])
  })

  it('renders edit title when editShare is provided', () => {
    const wrapper = mount(CreateShareModal, {
      props: {
        open: true,
        itemIds: [],
        editShare: {
          id: 's1',
          name: '旧分享',
          shareCode: 'abc123',
          hasPassword: false,
          viewCount: '0',
          status: 1,
          createTime: '2026-06-30 00:00:00',
          updateTime: '2026-06-30 00:00:00',
          items: [],
        },
      },
    })
    expect(wrapper.text()).toContain('编辑分享')
  })
})
