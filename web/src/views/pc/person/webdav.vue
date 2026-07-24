<script setup lang="ts">
/**
 * WebDAV 共享配置页
 * - 开启/关闭 WebDAV 访问
 * - 开启后展示访问地址，支持一键复制
 */
import { computed, onMounted, ref } from 'vue'
import { HardDrive } from '@lucide/vue'
import { getCurrentUserProfile, toggleWebDav } from '@/api/user'
import { useUserStore } from '@/store/user'
import { cn } from '@/utils/cn'
import type { UserWebDavToggleDto } from '@/types/auth'

const userStore = useUserStore()

const username = ref('')
const enabled = ref(false)
const toggling = ref(false)
const copied = ref(false)

const webDavUrl = computed(() => `${window.location.origin}/dav/${username.value}`)

onMounted(async () => {
  const profile = await getCurrentUserProfile()
  username.value = profile.username
  enabled.value = profile.webdavEnabled ?? false
})

async function handleToggle() {
  if (toggling.value) return
  toggling.value = true
  try {
    const dto: UserWebDavToggleDto = { enabled: !enabled.value }
    const updated = await toggleWebDav(dto)
    enabled.value = updated.webdavEnabled ?? dto.enabled
    if (userStore.userInfo) {
      userStore.userInfo.webdavEnabled = enabled.value
    }
  } finally {
    toggling.value = false
  }
}

async function copyUrl() {
  try {
    await navigator.clipboard.writeText(webDavUrl.value)
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    // ignore
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl p-6">
    <div class="rounded-2xl bg-white p-6 shadow-card">
      <div class="mb-1 flex items-center gap-2">
        <HardDrive class="h-5 w-5 text-primary-600" />
        <h2 class="text-lg font-bold text-surface-900">
          WebDAV 访问
        </h2>
      </div>
      <p class="mb-5 text-sm text-surface-500">
        开启后可通过 WebDAV 客户端访问整个文件树。用户名即为账号用户名，密码为登录密码。
      </p>

      <div class="mb-5 flex items-center justify-between">
        <span class="text-sm text-surface-700">启用 WebDAV</span>
        <button
          :disabled="toggling"
          :class="
            cn(
              'relative inline-flex h-6 w-11 items-center rounded-full border transition-colors disabled:opacity-60',
              enabled ? 'border-primary-600 bg-primary-600' : 'border-slate-400 bg-slate-300'
            )
          "
          @click="handleToggle"
        >
          <span
            :class="
              cn(
                'inline-block h-4 w-4 transform rounded-full bg-white shadow-sm transition-transform',
                enabled ? 'translate-x-6' : 'translate-x-1'
              )
            "
          />
        </button>
      </div>

      <div
        v-if="enabled"
        class="space-y-2"
      >
        <label class="text-xs font-medium text-surface-700">访问地址</label>
        <div class="flex items-center gap-2">
          <input
            :value="webDavUrl"
            type="text"
            readonly
            class="flex-1 rounded-lg border border-surface-200 bg-white px-2 py-1.5 text-xs text-surface-600 outline-none"
          >
          <button
            class="rounded-lg bg-primary-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-primary-700"
            @click="copyUrl"
          >
            {{ copied ? '已复制' : '复制' }}
          </button>
        </div>
        <p class="text-xs leading-relaxed text-amber-600">
          注意：通过 WebDAV 删除的文件不会进入回收站，且跨本地/远程目录的操作会被拒绝。HTTP 环境下会明文传输密码。
        </p>
      </div>
    </div>
  </div>
</template>
