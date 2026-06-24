<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ChangePasswordDto } from '@/types/auth'
import { KeyRound, X } from '@lucide/vue'
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
  submitting: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  submit: [dto: ChangePasswordDto]
}>()

const localOpen = computed({
  get: () => props.open,
  set: (value) => emit('update:open', value),
})

const form = ref<ChangePasswordDto>({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const errorMessage = ref('')

watch(localOpen, (open) => {
  if (open) {
    form.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
    errorMessage.value = ''
  }
})

function handleSubmit() {
  errorMessage.value = ''
  if (!form.value.currentPassword || !form.value.newPassword || !form.value.confirmPassword) {
    errorMessage.value = '请填写所有密码字段'
    return
  }
  if (form.value.newPassword.length < 6) {
    errorMessage.value = '新密码长度不能少于6位'
    return
  }
  if (form.value.newPassword !== form.value.confirmPassword) {
    errorMessage.value = '两次输入的新密码不一致'
    return
  }
  emit('submit', { ...form.value })
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
            <KeyRound class="h-5 w-5 text-primary-600" />
            <DialogTitle class="text-lg font-bold text-surface-900">
              修改密码
            </DialogTitle>
          </div>
          <DialogClose
            class="rounded-lg p-1 text-surface-400 outline-none hover:bg-surface-100 hover:text-surface-600"
          >
            <X class="h-5 w-5" />
          </DialogClose>
        </div>
        <DialogDescription class="mb-4 text-sm text-surface-500">
          修改当前账号的登录密码
        </DialogDescription>

        <div class="mb-6 space-y-4">
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">当前密码</label>
            <input
              v-model="form.currentPassword"
              type="password"
              placeholder="请输入当前密码"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">新密码</label>
            <input
              v-model="form.newPassword"
              type="password"
              placeholder="请输入新密码（至少6位）"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">确认新密码</label>
            <input
              v-model="form.confirmPassword"
              type="password"
              placeholder="请再次输入新密码"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>
          <p
            v-if="errorMessage"
            class="text-sm text-red-600"
          >
            {{ errorMessage }}
          </p>
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
