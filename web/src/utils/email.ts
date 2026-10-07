/**
 * 邮箱格式轻量校验工具
 *
 * 仅用于前端提交前的预检提示，真正的校验以后端为准。
 */
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

/**
 * 判断邮箱是否为非空且格式合法
 */
export function isValidEmail(value: string): boolean {
  return EMAIL_PATTERN.test(value.trim())
}

/**
 * 返回邮箱校验的错误提示，合法时返回空字符串
 */
export function emailValidationMessage(value?: string | null): string {
  const email = value ?? ''
  if (!email.trim()) {
    return '邮箱不能为空'
  }
  if (!isValidEmail(email)) {
    return '邮箱格式不正确'
  }
  return ''
}
