import { describe, it, expect, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ConflictResolveModal from './ConflictResolveModal.vue'
import type { ConflictItemVo } from '@/types/file'

function buildFileConflict(): ConflictItemVo {
  return {
    sourceId: '101',
    sourceName: 'hello.txt',
    sourceType: 'file',
    existingId: '201',
    existingName: 'hello.txt',
    existingType: 'file',
  }
}

function buildFolderConflict(): ConflictItemVo {
  return {
    sourceId: '102',
    sourceName: 'docs',
    sourceType: 'folder',
    existingId: '202',
    existingName: 'docs',
    existingType: 'folder',
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

describe('ConflictResolveModal', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('renders conflict list when open', async () => {
    mount(ConflictResolveModal, {
      props: {
        open: true,
        conflicts: [buildFileConflict(), buildFolderConflict()],
        title: '恢复冲突',
      },
      attachTo: document.body,
    })
    await flushPromises()

    expect(document.body.textContent).toContain('恢复冲突')
    expect(document.body.textContent).toContain('hello.txt')
    expect(document.body.textContent).toContain('docs')
  })

  it('defaults file conflicts to auto_rename and folder conflicts to skip', async () => {
    const wrapper = mount(ConflictResolveModal, {
      props: {
        open: true,
        conflicts: [buildFileConflict(), buildFolderConflict()],
      },
      attachTo: document.body,
    })
    await flushPromises()

    await findButtonByText('确认')!.click()

    expect(wrapper.emitted('confirm')).toHaveLength(1)
    expect(wrapper.emitted('confirm')![0][0]).toEqual({
      '101': 'auto_rename',
      '102': 'skip',
    })
  })

  it('emits selected strategies on confirm', async () => {
    const wrapper = mount(ConflictResolveModal, {
      props: {
        open: true,
        conflicts: [buildFileConflict(), buildFolderConflict()],
      },
      attachTo: document.body,
    })
    await flushPromises()

    // File conflict: skip / overwrite / auto_rename
    const strategyButtons = findStrategyButtons()
    expect(strategyButtons).toHaveLength(5)

    await strategyButtons[0].click()
    await strategyButtons[4].click()

    await findButtonByText('确认')!.click()

    expect(wrapper.emitted('confirm')).toHaveLength(1)
    expect(wrapper.emitted('confirm')![0][0]).toEqual({
      '101': 'skip',
      '102': 'overwrite',
    })
  })

  it('hides auto_rename option for folder conflicts', async () => {
    mount(ConflictResolveModal, {
      props: {
        open: true,
        conflicts: [buildFolderConflict()],
      },
      attachTo: document.body,
    })
    await flushPromises()

    const buttons = findStrategyButtons()
    expect(buttons).toHaveLength(2)
    expect(buttons[0].textContent).toContain('跳过')
    expect(buttons[1].textContent).toContain('覆盖')
  })

  it('emits cancel on cancel click', async () => {
    const wrapper = mount(ConflictResolveModal, {
      props: {
        open: true,
        conflicts: [buildFileConflict()],
      },
      attachTo: document.body,
    })
    await flushPromises()

    await findButtonByText('取消')!.click()

    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })
})
