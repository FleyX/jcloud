<script setup lang="ts">
/**
 * 个人资料页
 * - 查看并修改当前账号的昵称、邮箱
 * - 修改登录密码
 */
import { onMounted, ref } from 'vue'
import { User, KeyRound } from '@lucide/vue'
import {
  getCurrentUserProfile,
  updateCurrentUserProfile,
  changePassword,
} from '@/api/user'
import DeviceSessionPanel from '@/components/DeviceSessionPanel.vue'
import { useUserStore } from '@/store/user'
import type { ChangePasswordDto, UserProfileUpdateDto, UserProfileVo } from '@/types/auth'

const userStore = useUserStore()

const profile = ref<UserProfileVo | null>(null)
const profileForm = ref<UserProfileUpdateDto>({ email: '', nickname: '' })
const profileSubmitting = ref(false)

const passwordForm = ref<ChangePasswordDto>({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})
const passwordSubmitting = ref(false)
const passwordError = ref('')
const passwordSuccess = ref(false)

onMounted(async () => {
  profile.value = await getCurrentUserProfile()
  profileForm.value = {
    email: profile.value.email ?? '',
    nickname: profile.value.nickname ?? '',
  }
})

async function handleProfileSubmit() {
  profileSubmitting.value = true
  try {
    await updateCurrentUserProfile({ ...profileForm.value })
    await userStore.fetchCurrentUser()
  } finally {
    profileSubmitting.value = false
  }
}

async function handlePasswordSubmit() {
  passwordError.value = ''
  passwordSuccess.value = false
  const form = passwordForm.value
  if (!form.currentPassword || !form.newPassword || !form.confirmPassword) {
    passwordError.value = '请填写所有密码字段'
    return
  }
  if (form.newPassword.length < 6) {
    passwordError.value = '新密码长度不能少于6位'
    return
  }
  if (form.newPassword !== form.confirmPassword) {
    passwordError.value = '两次输入的新密码不一致'
    return
  }
  passwordSubmitting.value = true
  try {
    await changePassword({ ...form })
    passwordForm.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
    passwordSuccess.value = true
  } finally {
    passwordSubmitting.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl space-y-6 p-6">
    <!-- 个人资料 -->
    <div class="rounded-2xl bg-white p-6 shadow-card">
      <div class="mb-1 flex items-center gap-2">
        <User class="h-5 w-5 text-primary-600" />
        <h2 class="text-lg font-bold text-surface-900">
          个人资料
        </h2>
      </div>
      <p class="mb-5 text-sm text-surface-500">
        查看并修改当前账号的个人信息
      </p>

      <div class="mb-5 space-y-4">
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
            v-model="profileForm.nickname"
            type="text"
            placeholder="请输入昵称"
            class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
          >
        </div>
        <div>
          <label class="mb-1 block text-xs font-medium text-surface-700">邮箱</label>
          <input
            v-model="profileForm.email"
            type="email"
            placeholder="请输入邮箱"
            class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
          >
        </div>
      </div>

      <div class="flex justify-end">
        <button
          :disabled="profileSubmitting"
          class="rounded-xl bg-primary-600 px-4 py-2 text-sm font-medium text-white shadow-soft transition-colors hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-70"
          @click="handleProfileSubmit"
        >
          {{ profileSubmitting ? '保存中...' : '保存' }}
        </button>
      </div>
    </div>

    <!-- 修改密码 -->
    <div class="rounded-2xl bg-white p-6 shadow-card">
      <div class="mb-1 flex items-center gap-2">
        <KeyRound class="h-5 w-5 text-primary-600" />
        <h2 class="text-lg font-bold text-surface-900">
          修改密码
        </h2>
      </div>
      <p class="mb-5 text-sm text-surface-500">
        修改当前账号的登录密码
      </p>

      <div class="mb-5 space-y-4">
        <div>
          <label class="mb-1 block text-xs font-medium text-surface-700">当前密码</label>
          <input
            v-model="passwordForm.currentPassword"
            type="password"
            placeholder="请输入当前密码"
            class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
          >
        </div>
        <div>
          <label class="mb-1 block text-xs font-medium text-surface-700">新密码</label>
          <input
            v-model="passwordForm.newPassword"
            type="password"
            placeholder="请输入新密码（至少6位）"
            class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
          >
        </div>
        <div>
          <label class="mb-1 block text-xs font-medium text-surface-700">确认新密码</label>
          <input
            v-model="passwordForm.confirmPassword"
            type="password"
            placeholder="请再次输入新密码"
            class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
          >
        </div>
        <p
          v-if="passwordError"
          class="text-sm text-red-600"
        >
          {{ passwordError }}
        </p>
        <p
          v-if="passwordSuccess"
          class="text-sm text-green-600"
        >
          密码修改成功
        </p>
      </div>

      <div class="flex justify-end">
        <button
          :disabled="passwordSubmitting"
          class="rounded-xl bg-primary-600 px-4 py-2 text-sm font-medium text-white shadow-soft transition-colors hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-70"
          @click="handlePasswordSubmit"
        >
          {{ passwordSubmitting ? '保存中...' : '保存' }}
        </button>
      </div>
    </div>

    <!-- 登录设备 -->
    <DeviceSessionPanel />
  </div>
</template>
