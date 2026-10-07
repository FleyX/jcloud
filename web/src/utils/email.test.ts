import { describe, expect, it } from 'vitest'
import { emailValidationMessage, isValidEmail } from './email'

describe('isValidEmail', () => {
  it('accepts common valid addresses', () => {
    expect(isValidEmail('user@example.com')).toBe(true)
    expect(isValidEmail('first.last+tag@sub.example.cn')).toBe(true)
    expect(isValidEmail('  spaced@example.com  ')).toBe(true)
  })

  it('rejects empty, blank and malformed values', () => {
    expect(isValidEmail('')).toBe(false)
    expect(isValidEmail('   ')).toBe(false)
    expect(isValidEmail('not-an-email')).toBe(false)
    expect(isValidEmail('user@')).toBe(false)
    expect(isValidEmail('@example.com')).toBe(false)
    expect(isValidEmail('user@example')).toBe(false)
    expect(isValidEmail('user name@example.com')).toBe(false)
  })
})

describe('emailValidationMessage', () => {
  it('returns empty message for valid email', () => {
    expect(emailValidationMessage('user@example.com')).toBe('')
  })

  it('reports empty and malformed email with distinct messages', () => {
    expect(emailValidationMessage('')).toBe('邮箱不能为空')
    expect(emailValidationMessage('   ')).toBe('邮箱不能为空')
    expect(emailValidationMessage('not-an-email')).toBe('邮箱格式不正确')
  })
})
