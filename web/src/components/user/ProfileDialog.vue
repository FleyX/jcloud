<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { UserProfileUpdateDto, UserProfileVo } from '@/types/auth'
import { User, X, HardDrive } from '@lucide/vue'
import { cn } from '@/utils/cn'
import {
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
  DialogTitle,
} from 'radix-vue'

const props = defineProps<{
  open: boolean
  profile: UserProfileVo | null
  submitting: boolean
  webdavEnabled?: boolean
  webdavToggling?: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  submit: [dto: UserProfileUpdateDto]
  'toggle-webdav': [enabled: boolean]
}>()

const localOpen = computed({
  get: () => props.open,
  set: (value) => emit('update:open', value),
})

const form = defineModel<UserProfileUpdateDto>({ default: () => ({}) })
const copied = ref(false)

watch(
  () => props.profile,
  (profile) => {
    if (profile) {
      form.value = {
        email: profile.email ?? '',
        nickname: profile.nickname ?? '',
      }
    }
  },
  { immediate: true },
)

function handleSubmit() {
  emit('submit', { ...form.value })
}

function webDavUrl() {
  if (!props.profile) return ''
  return `${window.location.origin}/dav/${props.profile.username}`
}

async function copyUrl() {
  try {
    await navigator.clipboard.writeText(webDavUrl())
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    // ignore
  }
}
</script>

<template>
  <DialogRoot v-model:open="localOpen">
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 z-40 bg-surface-900/40 backdrop-blur-sm" />
      <DialogContent
        class="fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2 rounded-2xl bg-white p-6 shadow-soft"
      >
        <div class="mb-4 flex items-center justify-between">
          <div class="flex items-center gap-2">
            <User class="h-5 w-5 text-primary-600" />
            <DialogTitle class="text-lg font-bold text-surface-900">
              个人信息
            </DialogTitle>
          </div>
          <DialogClose
            class="rounded-lg p-1 text-surface-400 outline-none hover:bg-surface-100 hover:text-surface-600"
          >
            <X class="h-5 w-5" />
          </DialogClose>
        </div>
        <DialogDescription class="mb-4 text-sm text-surface-500">
          查看并修改当前账号的个人信息
        </DialogDescription>

        <div class="mb-6 space-y-4">
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">用户名</label>
            <input
              :value="profile?.username"
              type="text"
              disabled
              class="w-full rounded-xl border border-surface-200 bg-surface-100 px-3 py-2 text-sm text-surface-500 outline-none disabled:cursor-not-allowed"
            >
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">昵称</label>
            <input
              v-model="form.nickname"
              type="text"
              placeholder="请输入昵称"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">邮箱</label>
            <input
              v-model="form.email"
              type="email"
              placeholder="请输入邮箱"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>
        </div>

        <div class="mb-6 rounded-xl border border-surface-200 bg-surface-50 p-4">
          <div class="mb-3 flex items-center gap-2">
            <HardDrive class="h-4 w-4 text-primary-600" />
            <h3 class="text-sm font-semibold text-surface-900">WebDAV 访问</h3>
          </div>
          <p class="mb-3 text-xs leading-relaxed text-surface-500">
            开启后可通过 WebDAV 客户端访问整个文件树。用户名即为账号用户名，密码为登录密码。
          </p>
          <div class="mb-3 flex items-center justify-between">
            <span class="text-sm text-surface-700">启用 WebDAV</span>
            <button
              :disabled="webdavToggling"
              :class="
                cn(
                  'relative inline-flex h-6 w-11 items-center rounded-full transition-colors disabled:opacity-60',
                  webdavEnabled ? 'bg-primary-600' : 'bg-surface-300'
                )
              "
              @click="$emit('toggle-webdav', !webdavEnabled)"
            >
              <span
                :class="
                  cn(
                    'inline-block h-4 w-4 transform rounded-full bg-white transition-transform',
                    webdavEnabled ? 'translate-x-6' : 'translate-x-1'
                  )
                "
              />
            </button>
          </div>
          <div v-if="webdavEnabled" class="space-y-2">
            <label class="text-xs font-medium text-surface-700">访问地址</label>
            <div class="flex items-center gap-2">
              <input
                :value="webDavUrl()"
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

        <div class="flex justify-end gap-3">
          <DialogClose as-child>
            <button
              :disabled="submitting"
              class="rounded-xl border border-surface-200 bg-white px-4 py-2 text-sm font-medium text-surface-700 transition-colors hover:bg-surface-50 disabled:cursor-not-allowed disabled:opacity-60"
            >
              取消
            </button>
          </DialogClose>
          <button
            :disabled="submitting"
            class="rounded-xl bg-primary-600 px-4 py-2 text-sm font-medium text-white shadow-soft transition-colors hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-70"
            @click="handleSubmit"
          >
            {{ submitting ? '保存中...' : '保存' }}
          </button>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
