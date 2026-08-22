import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import TranscodeConfigCard from './TranscodeConfigCard.vue'
import type { TranscodeConfigDto } from '@/types/media'

const mocks = vi.hoisted(() => ({
  fetchTranscodeConfig: vi.fn(),
  updateTranscodeConfig: vi.fn(),
}))

vi.mock('@/api/media', () => mocks)

function buildConfig(overrides: Partial<TranscodeConfigDto> = {}): TranscodeConfigDto {
  return { hwaccel: 'none', device: '', threads: 0, ...overrides }
}

async function mountCard(config: TranscodeConfigDto = buildConfig()) {
  mocks.fetchTranscodeConfig.mockResolvedValue(config)
  const wrapper = mount(TranscodeConfigCard, {
    global: { plugins: [createPinia()] },
  })
  await flushPromises()
  return wrapper
}

describe('TranscodeConfigCard 硬解方式选项', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it('下拉恰好渲染 4 个硬解档位且不含 auto', async () => {
    const wrapper = await mountCard()

    const options = wrapper.findAll('option')
    expect(options).toHaveLength(4)
    expect(options.map((o) => o.element.value)).toEqual(['vaapi', 'qsv', 'nvenc', 'none'])
    expect(options.map((o) => o.element.value)).not.toContain('auto')
  })

  it('GET 返回 qsv 时下拉回显 qsv 及设备路径', async () => {
    const wrapper = await mountCard(buildConfig({ hwaccel: 'qsv', device: '/dev/dri/renderD128', threads: 4 }))

    const select = wrapper.find('select').element as HTMLSelectElement
    expect(select.value).toBe('qsv')
    const deviceInput = wrapper.find<HTMLInputElement>('input[placeholder="/dev/dri/renderD128"]')
    expect(deviceInput.element.value).toBe('/dev/dri/renderD128')
  })

  it('保存时 updateTranscodeConfig 收到所选档位', async () => {
    const wrapper = await mountCard()
    mocks.updateTranscodeConfig.mockResolvedValue(undefined)

    await wrapper.find('select').setValue('vaapi')
    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(mocks.updateTranscodeConfig).toHaveBeenCalledTimes(1)
    expect(mocks.updateTranscodeConfig).toHaveBeenCalledWith(expect.objectContaining({ hwaccel: 'vaapi' }))
  })
})
