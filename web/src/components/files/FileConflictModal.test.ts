import { describe, it, expect, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import FileConflictModal from './FileConflictModal.vue'
import type { ConflictItemVo } from '@/types/file'

function buildFileConflict(name: string): ConflictItemVo {
  return {
    sourceId: `id-${name}`,
    sourceName: name,
    sourceType: 'file',
    existingId: `existing-${name}`,
    existingName: name,
    existingType: 'file',
    type: 'file',
  }
}

function buildFolderConflict(name: string): ConflictItemVo {
  return {
    sourceId: `folder-${name}`,
    sourceName: name,
    sourceType: 'folder',
    existingId: `existing-folder-${name}`,
    existingName: name,
    existingType: 'folder',
    type: 'folder',
    autoMerge: true,
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

describe('FileConflictModal', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('renders file conflict list and folder merge summary', async () => {
    mount(FileConflictModal, {
      props: {
        open: true,
        conflicts: [buildFileConflict('a.txt'), buildFolderConflict('docs')],
        title: '上传冲突',
      },
      attachTo: document.body,
    })
    await flushPromises()

    expect(document.body.textContent).toContain('上传冲突')
    expect(document.body.textContent).toContain('a.txt')
    expect(document.body.textContent).toContain('1 个文件夹将自动合并')
  })

  it('defaults all conflicts to keep', async () => {
    const wrapper = mount(FileConflictModal, {
      props: {
        open: true,
        conflicts: [buildFileConflict('a.txt'), buildFileConflict('b.txt')],
      },
      attachTo: document.body,
    })
    await flushPromises()

    await findButtonByText('确认')!.click()

    expect(wrapper.emitted('confirm')).toHaveLength(1)
    expect(wrapper.emitted('confirm')![0][0]).toEqual({
      'id-a.txt': 'keep',
      'id-b.txt': 'keep',
    })
  })

  it('supports apply all skip', async () => {
    const wrapper = mount(FileConflictModal, {
      props: {
        open: true,
        conflicts: [buildFileConflict('a.txt'), buildFileConflict('b.txt')],
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
    const wrapper = mount(FileConflictModal, {
      props: {
        open: true,
        conflicts: [buildFileConflict('a.txt'), buildFileConflict('b.txt')],
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
      'id-b.txt': 'keep',
    })
  })

  it('enables confirm by default because keep is preselected', async () => {
    const wrapper = mount(FileConflictModal, {
      props: {
        open: true,
        conflicts: [buildFileConflict('a.txt')],
      },
      attachTo: document.body,
    })
    await flushPromises()

    const confirmButton = findButtonByText('确认')!
    expect(confirmButton.disabled).toBe(false)

    await confirmButton.click()
    expect(wrapper.emitted('confirm')).toHaveLength(1)
  })

  it('emits cancel on cancel click', async () => {
    const wrapper = mount(FileConflictModal, {
      props: {
        open: true,
        conflicts: [buildFileConflict('a.txt')],
      },
      attachTo: document.body,
    })
    await flushPromises()

    await findButtonByText('取消')!.click()

    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })
})
