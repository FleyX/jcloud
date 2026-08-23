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
