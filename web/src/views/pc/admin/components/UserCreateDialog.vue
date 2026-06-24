<script setup lang="ts">
import { computed } from 'vue'
import type { UserSaveDto } from '@/types/auth'
import { Plus, X } from '@lucide/vue'
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
  form: UserSaveDto
  submitting: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  submit: []
}>()

const localOpen = computed({
  get: () => props.open,
  set: (value) => emit('update:open', value),
})
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
            <Plus class="h-5 w-5 text-primary-600" />
            <DialogTitle class="text-lg font-bold text-surface-900">
              新增用户
            </DialogTitle>
          </div>
          <DialogClose
            class="rounded-lg p-1 text-surface-400 outline-none hover:bg-surface-100 hover:text-surface-600"
          >
            <X class="h-5 w-5" />
          </DialogClose>
        </div>
        <DialogDescription class="mb-4 text-sm text-surface-500">
          请输入新用户的基本信息，初始密码将由系统记录。
        </DialogDescription>

        <div class="mb-6 space-y-4">
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">用户名</label>
            <input
              v-model="props.form.username"
              type="text"
              placeholder="请输入用户名"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">昵称</label>
            <input
              v-model="props.form.nickname"
              type="text"
              placeholder="请输入昵称"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">邮箱</label>
            <input
              v-model="props.form.email"
              type="email"
              placeholder="请输入邮箱"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">初始密码</label>
            <input
              v-model="props.form.password"
              type="password"
              placeholder="请输入初始密码"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
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
            @click="emit('submit')"
          >
            {{ submitting ? '保存中...' : '保存' }}
          </button>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
