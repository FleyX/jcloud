import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import SmtpConfigForm from './SmtpConfigForm.vue'
import type { SmtpFormModel } from '@/types/notification'

function buildModel(overrides: Partial<SmtpFormModel> = {}): SmtpFormModel {
  return {
    host: '',
    port: 465,
    username: '',
    password: '',
    encryption: 'ssl',
    fromAddress: '',
    fromName: '',
    ...overrides,
  }
}

function mountForm(model: SmtpFormModel, props: Record<string, string> = {}) {
  return mount(SmtpConfigForm, { props: { modelValue: model, ...props } })
}

describe('SmtpConfigForm', () => {
  it('renders the SMTP fields and encryption options', () => {
    const wrapper = mountForm(buildModel())

    expect(wrapper.findAll('input')).toHaveLength(6)
    expect(wrapper.findAll('button').map((button) => button.text())).toEqual(['无', 'SSL', 'STARTTLS'])
  })

  it('updates the bound form object in place when editing fields', async () => {
    const model = ref(buildModel())
    const wrapper = mountForm(model.value)

    await wrapper.find('input[placeholder="smtp.example.com"]').setValue('smtp.test.com')
    await wrapper.find('input[placeholder="465"]').setValue('587')
    await wrapper.find('input[placeholder="no-reply@example.com"]').setValue('mail@test.com')

    expect(model.value.host).toBe('smtp.test.com')
    expect(model.value.port).toBe(587)
    expect(model.value.fromAddress).toBe('mail@test.com')
  })

  it('selects the encryption option', async () => {
    const model = ref(buildModel())
    const wrapper = mountForm(model.value)

    await wrapper.findAll('button').find((button) => button.text() === 'STARTTLS')!.trigger('click')

    expect(model.value.encryption).toBe('starttls')
  })

  it('uses the provided password placeholder', () => {
    const wrapper = mountForm(buildModel(), { passwordPlaceholder: '已设置，留空不修改' })

    expect(wrapper.find('input[type="password"]').attributes('placeholder')).toBe('已设置，留空不修改')
  })
})
