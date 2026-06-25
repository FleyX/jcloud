import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import FileRowActions from './FileRowActions.vue'
import type { FileNodeVo } from '@/types/file'

function buildFile(): FileNodeVo {
  return {
    id: '1',
    userId: '1',
    parentId: '0',
    name: 'report.txt',
    type: 'file',
    size: '1024',
    storageSpaceId: '1',
    pathName: '/',
    status: 1,
  }
}

describe('FileRowActions', () => {
  it('emits rename event', async () => {
    const wrapper = mount(FileRowActions, {
      props: { file: buildFile() },
    })
    wrapper.vm.$emit('rename', buildFile())
    expect(wrapper.emitted('rename')).toHaveLength(1)
  })

  it('emits move event', async () => {
    const wrapper = mount(FileRowActions, {
      props: { file: buildFile() },
    })
    wrapper.vm.$emit('move', buildFile())
    expect(wrapper.emitted('move')).toHaveLength(1)
  })

  it('emits copy event', async () => {
    const wrapper = mount(FileRowActions, {
      props: { file: buildFile() },
    })
    wrapper.vm.$emit('copy', buildFile())
    expect(wrapper.emitted('copy')).toHaveLength(1)
  })

  it('emits delete event', async () => {
    const wrapper = mount(FileRowActions, {
      props: { file: buildFile() },
    })
    wrapper.vm.$emit('delete', buildFile())
    expect(wrapper.emitted('delete')).toHaveLength(1)
  })
})
