import { onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/store/user'
import { useNotificationStore } from '@/store/notification'
import { checkRemoteMountHealth } from '@/api/remote-mount'

const STORAGE_KEY = 'remote_mount_health_check_date'

function getToday(): string {
  return new Date().toISOString().slice(0, 10)
}

function shouldCheckToday(): boolean {
  return localStorage.getItem(STORAGE_KEY) !== getToday()
}

function markCheckedToday(): void {
  localStorage.setItem(STORAGE_KEY, getToday())
}

/**
 * 每天首次进入系统时检查远程挂载健康状态，并提示有故障的挂载点。
 * 公开路由或未登录时不执行。
 */
export function useRemoteMountHealthCheck(): void {
  const route = useRoute()
  const userStore = useUserStore()
  const notificationStore = useNotificationStore()

  async function runCheck() {
    if (route.meta.public) return
    if (!userStore.isLoggedIn) return
    if (!shouldCheckToday()) return

    try {
      const results = await checkRemoteMountHealth()
      markCheckedToday()
      const failed = results.filter((item) => item.status === 'error')
      if (failed.length === 0) return

      if (failed.length === 1) {
        notificationStore.error(`远程挂载 "${failed[0].name}" 连接异常：${failed[0].message || '请检查配置'}`)
        return
      }
      notificationStore.error(`以下远程挂载连接异常：${failed.map((item) => item.name).join('、')}`)
    } catch {
      // request.ts 已统一处理异常提示
    }
  }

  onMounted(runCheck)
}
