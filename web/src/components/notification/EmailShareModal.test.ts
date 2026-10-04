import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import EmailShareModal from './EmailShareModal.vue'
import type { FileNodeVo } from '@/types/file'

const mockCreateShare = vi.fn()
const mockSendEmailShareLink = vi.fn()
const mockGetRecentRecipients = vi.fn()

vi.mock('@/api/share', () => ({
  createShare: (...args: unknown[]) => mockCreateShare(...args),
  sendEmailShareLink: (...args: unknown[]) => mockSendEmailShareLink(...args),
  getRecentRecipients: (...args: unknown[]) => mockGetRecentRecipients(...args),
}))

vi.mock('@/store/notification', () => ({
  useNotificationStore: () => ({ success: vi.fn(), error: vi.fn() }),
}))

function fileNode(id: string, name: string, type: 'file' | 'folder' = 'file'): FileNodeVo {
  return {
    id,
    userId: 'u1',
    parentId: '0',
    name,
    type,
    size: '1024',
    storageSpaceId: 's1',
    pathName: name,
    status: 1,
  }
}

async function openModal(items: FileNodeVo[]) {
  const wrapper = mount(EmailShareModal, { props: { open: false, items } })
  await wrapper.setProps({ open: true })
  await flushPromises()
  return wrapper
}

function findButton(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((w) => w.text().includes(text))
}

async function addRecipient(wrapper: ReturnType<typeof mount>, email: string) {
  const input = wrapper.find('input[placeholder="输入邮箱后回车添加"]')
  await input.setValue(email)
  await input.trigger('keyup.enter')
}

describe('EmailShareModal', () => {
  beforeEach(() => {
    mockCreateShare.mockReset()
    mockSendEmailShareLink.mockReset()
    mockGetRecentRecipients.mockReset()
    mockGetRecentRecipients.mockResolvedValue({ smtpConfigured: true, recipients: [] })
  })

  it('shows disabled attachment mode for file-only selection', async () => {
    const wrapper = await openModal([fileNode('f1', 'a.pdf')])
    const attachment = findButton(wrapper, '附件直发')
    expect(attachment).toBeDefined()
    expect(attachment!.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('链接分享')
  })

  it('hides attachment mode when selection contains a folder', async () => {
    const wrapper = await openModal([fileNode('d1', 'docs', 'folder')])
    expect(findButton(wrapper, '附件直发')).toBeUndefined()
  })

  it('adds recipients from recent list and input, and removes a chip', async () => {
    mockGetRecentRecipients.mockResolvedValue({ smtpConfigured: true, recipients: ['recent@example.com'] })
    const wrapper = await openModal([fileNode('f1', 'a.pdf')])

    await findButton(wrapper, 'recent@example.com')!.trigger('click')
    expect(wrapper.text()).toContain('recent@example.com')

    await addRecipient(wrapper, 'new@example.com')
    expect(wrapper.text()).toContain('new@example.com')

    const chip = wrapper.findAll('span').find((w) => w.text().includes('new@example.com'))
    expect(chip).toBeDefined()
    await chip!.find('button').trigger('click')
    expect(wrapper.text()).not.toContain('new@example.com')
  })

  it('rejects malformed recipient email', async () => {
    const wrapper = await openModal([fileNode('f1', 'a.pdf')])
    await addRecipient(wrapper, 'not-an-email')
    expect(wrapper.text()).toContain('邮箱格式不正确')
  })

  it('shows guide and disables submit when smtp is not configured', async () => {
    mockGetRecentRecipients.mockResolvedValue({ smtpConfigured: false, recipients: [] })
    const wrapper = await openModal([fileNode('f1', 'a.pdf')])

    expect(wrapper.text()).toContain('尚未配置发件邮箱')
    await addRecipient(wrapper, 'new@example.com')
    expect(findButton(wrapper, '创建并发送')!.attributes('disabled')).toBeDefined()
  })

  it('creates share then sends link mail with share url and password', async () => {
    mockCreateShare.mockResolvedValue({ shareCode: 'abc12345' })
    mockSendEmailShareLink.mockResolvedValue(undefined)
    const wrapper = await openModal([fileNode('f1', 'a.pdf')])

    await addRecipient(wrapper, 'to@example.com')

    const passwordToggle = wrapper.findAll('button').find((w) => w.classes().includes('rounded-full'))!
    await passwordToggle.trigger('click')
    await wrapper.find('input[placeholder="设置访问密码"]').setValue('pass123')

    await findButton(wrapper, '创建并发送')!.trigger('click')
    await flushPromises()

    expect(mockCreateShare).toHaveBeenCalledTimes(1)
    const createPayload = mockCreateShare.mock.calls[0][0] as Record<string, unknown>
    expect(createPayload.name).toBe('a.pdf')
    expect(createPayload.fileNodeIds).toEqual(['f1'])
    expect(createPayload.password).toBe('pass123')

    expect(mockSendEmailShareLink).toHaveBeenCalledTimes(1)
    const sendPayload = mockSendEmailShareLink.mock.calls[0][0] as Record<string, unknown>
    expect(sendPayload.shareCode).toBe('abc12345')
    expect(sendPayload.shareUrl).toBe(`${window.location.origin}/s/abc12345`)
    expect(sendPayload.password).toBe('pass123')
    expect(sendPayload.recipients).toEqual(['to@example.com'])
    expect(wrapper.emitted('sent')).toHaveLength(1)
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})
