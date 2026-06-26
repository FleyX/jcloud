import { describe, it, expect, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import UploadConflictModal from './UploadConflictModal.vue'
import type { ConflictItemVo } from '@/types/file'

function buildConflict(name: string): ConflictItemVo {
  return {
    sourceId: `id-${name}`,
    sourceName: name,
    sourceType: 'file',
    existingId: `existing-${name}`,
    existingName: name,
    existingType: 'file',
  }
}

function findButtonByText(text: string): HTMLButtonElement | undefined {
  return Array.from(document.querySelectorAll('button')).find((b) =>
    b.textContent?.includes(text),
  ) as HTMLButtonElement | undefined
}

function findStrategyButtons(): HTMLButtonElement[] {
  return Array.from(document.querySelectorAll('button')).filter(
    (b) =>
      !b.textContent?.includes('确认') &&
      !b.textContent?.includes('取消') &&
      !b.textContent?.includes('全部') &&
      b.textContent?.trim().length > 0,
  ) as HTMLButtonElement[]
}

describe('UploadConflictModal', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('renders conflict list when open', async () => {
    mount(UploadConflictModal, {
      props: {
        open: true,
        conflicts: [buildConflict('a.txt'), buildConflict('b.txt')],
      },
      attachTo: document.body,
    })
    await flushPromises()

    expect(document.body.textContent).toContain('a.txt')
    expect(document.body.textContent).toContain('b.txt')
  })

  it('defaults all conflicts to auto_rename', async () => {
    const wrapper = mount(UploadConflictModal, {
      props: {
        open: true,
        conflicts: [buildConflict('a.txt'), buildConflict('b.txt')],
      },
      attachTo: document.body,
    })
    await flushPromises()

    await findButtonByText('确认')!.click()

    expect(wrapper.emitted('confirm')).toHaveLength(1)
    expect(wrapper.emitted('confirm')![0][0]).toEqual({
      'id-a.txt': 'auto_rename',
      'id-b.txt': 'auto_rename',
    })
  })

  it('supports apply all skip', async () => {
    const wrapper = mount(UploadConflictModal, {
      props: {
        open: true,
        conflicts: [buildConflict('a.txt'), buildConflict('b.txt')],
      },
      attachTo: document.body,
    })
    await flushPromises()

    await findButtonByText('全部跳过')!.click()
    await findButtonByText('确认')!.click()

    expect(wrapper.emitted('confirm')).toHaveLength(1)
    expect(wrapper.emitted('confirm')![0][0]).toEqual({
      'id-a.txt': 'skip',
      'id-b.txt': 'skip',
    })
  })

  it('supports per-item strategy change', async () => {
    const wrapper = mount(UploadConflictModal, {
      props: {
        open: true,
        conflicts: [buildConflict('a.txt'), buildConflict('b.txt')],
      },
      attachTo: document.body,
    })
    await flushPromises()

    const buttons = findStrategyButtons()
    expect(buttons).toHaveLength(6)
    // first conflict: overwrite
    await buttons[1].click()
    await findButtonByText('确认')!.click()

    expect(wrapper.emitted('confirm')![0][0]).toEqual({
      'id-a.txt': 'overwrite',
      'id-b.txt': 'auto_rename',
    })
  })

  it('emits cancel on cancel click', async () => {
    const wrapper = mount(UploadConflictModal, {
      props: {
        open: true,
        conflicts: [buildConflict('a.txt')],
      },
      attachTo: document.body,
    })
    await flushPromises()

    await findButtonByText('取消')!.click()

    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })
})
