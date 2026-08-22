import { createRouter, createWebHistory } from 'vue-router'
import { setupGuard } from './guard'
import { publicRoutes } from './routes'

const router = createRouter({
  history: createWebHistory(),
  routes: publicRoutes,
})

setupGuard(router)

export { isGuardInFlight } from './guard'

export default router