/**
 * 401 未授权错误。request 层在登录态失效时抛出，
 * 路由守卫据此将导航重定向到登录页（区别于网络异常等需要停滞在守卫的错误）。
 */
export class UnauthorizedError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'UnauthorizedError'
  }
}

/**
 * 业务错误。request 层 silent 模式下不弹全局通知，
 * 由调用方自行决定处理或回落（如收录反查未命中回落预览弹窗）。
 */
export class ApiError extends Error {
  constructor(
    public readonly code: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}
