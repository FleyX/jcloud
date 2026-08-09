import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import MobileFilesIndex from './index.vue'
import type { FileNodeVo } from '@/types/file'

const mockFetchFilePage = vi.fn()
const mockUploadBatch = vi.fn()

vi.mock('@/api/file', () => ({
  fetchFilePage: (...args: unknown[]) => mockFetchFilePage(...args),
  downloadFile: vi.fn(),
}))

vi.mock('@/composables/useBatchUpload', () => ({
  useBatchUpload: () => ({ uploadBatch: mockUploadBatch }),
}))

// jsdom 无 DOMMatrix，pdfjs-dist 导入即失败；预览抽屉在用例中仅 stub，无需真实 PDF 组件
vi.mock('@/components/files/PdfPreview.vue', () => ({
  default: { name: 'PdfPreview', template: '<div />' },
}))

function buildFileNode(): FileNodeVo {
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

async function mountPage() {
  setActivePinia(createPinia())
  const router = createRouter({
    history: createWebHistory(),
    routes: [{ path: '/files', component: MobileFilesIndex }],
  })
  const wrapper = mount(MobileFilesIndex, {
    global: {
      plugins: [router],
      stubs: {
        MobileBatchActionBar: { template: '<div>batch-bar</div>' },
        FilePreviewDrawer: { template: '<div>preview</div>' },
        FileConflictModal: { template: '<div>conflict</div>' },
        CreateShareModal: { template: '<div>share</div>' },
        MoveCopyModal: { template: '<div>movecopy</div>' },
      },
    },
  })
  await router.push('/files')
  await router.isReady()
  await flushPromises()
  return { wrapper }
}

describe('MobileFilesIndex', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders file list after loading', async () => {
    mockFetchFilePage.mockResolvedValue({ records: [buildFileNode()] })
    const { wrapper } = await mountPage()

    expect(mockFetchFilePage).toHaveBeenCalled()
    expect(wrapper.text()).toContain('report.txt')
  })

  it('renders empty state when no files', async () => {
    mockFetchFilePage.mockResolvedValue({ records: [] })
    const { wrapper } = await mountPage()

    expect(wrapper.text()).toContain('暂无文件')
  })
})
