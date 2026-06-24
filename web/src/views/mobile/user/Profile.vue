<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { User, KeyRound, LogOut, ChevronRight } from '@lucide/vue'
import { useUserStore } from '@/store/user'
import { getCurrentUserProfile, updateCurrentUserProfile, changePassword } from '@/api/user'
import ProfileDialog from '@/components/user/ProfileDialog.vue'
import ChangePasswordDialog from '@/components/user/ChangePasswordDialog.vue'
import { cn } from '@/utils/cn'
import type { ChangePasswordDto, UserProfileUpdateDto, UserProfileVo } from '@/types/auth'

const router = useRouter()
const userStore = useUserStore()

const profileOpen = ref(false)
const passwordOpen = ref(false)
const profileSubmitting = ref(false)
const passwordSubmitting = ref(false)
const profile = ref<UserProfileVo | null>(null)
const profileForm = ref<UserProfileUpdateDto>({ email: '', nickname: '' })

async function openProfile() {
  profile.value = await getCurrentUserProfile()
  profileForm.value = {
    email: profile.value.email ?? '',
    nickname: profile.value.nickname ?? '',
  }
  profileOpen.value = true
}

async function handleProfileSubmit(dto: UserProfileUpdateDto) {
  profileSubmitting.value = true
  try {
    await updateCurrentUserProfile(dto)
    await userStore.fetchCurrentUser()
    profileOpen.value = false
  } finally {
    profileSubmitting.value = false
  }
}

async function handlePasswordSubmit(dto: ChangePasswordDto) {
  passwordSubmitting.value = true
  try {
    await changePassword(dto)
    passwordOpen.value = false
  } finally {
    passwordSubmitting.value = false
  }
}

function handleLogout() {
  userStore.logoutAction()
  router.push('/login')
}

const userRoleLabel = userStore.isAdmin ? '超级管理员' : '普通用户'
</script>

<template>
  <div class="flex min-h-full flex-col bg-surface-50 p-4">
    <!-- 用户信息卡片 -->
    <div class="mb-4 rounded-2xl bg-white p-5 shadow-card">
      <div class="flex items-center gap-4">
        <div
          class="flex h-14 w-14 items-center justify-center rounded-full bg-primary-100 text-xl font-bold text-primary-700"
        >
          {{ userStore.userInfo?.username?.charAt(0).toUpperCase() || 'U' }}
        </div>
        <div class="flex-1">
          <h1 class="text-lg font-bold text-surface-900">
            {{ userStore.userInfo?.username || '未登录' }}
          </h1>
          <p class="text-sm text-surface-500">
            {{ userRoleLabel }}
          </p>
        </div>
      </div>
      <div class="mt-4 grid grid-cols-2 gap-3 text-sm">
        <div>
          <p class="text-xs text-surface-400">
            昵称
          </p>
          <p class="font-medium text-surface-700">
            {{ userStore.userInfo?.nickname || '-' }}
          </p>
        </div>
        <div>
          <p class="text-xs text-surface-400">
            邮箱
          </p>
          <p class="font-medium text-surface-700">
            {{ userStore.userInfo?.email || '-' }}
          </p>
        </div>
      </div>
    </div>

    <!-- 操作列表 -->
    <div class="rounded-2xl bg-white shadow-card">
      <button
        v-for="(action, index) in [
          { key: 'profile', icon: User, label: '个人信息' },
          { key: 'password', icon: KeyRound, label: '修改密码' },
          { key: 'logout', icon: LogOut, label: '退出登录' },
        ]"
        :key="action.key"
        :class="
          cn(
            'flex w-full items-center gap-3 px-4 py-4 text-left transition-colors',
            action.key === 'logout' ? 'text-red-600 hover:bg-red-50' : 'text-surface-700 hover:bg-surface-50',
            index !== 2 && 'border-b border-surface-100'
          )
        "
        @click="action.key === 'profile' ? openProfile() : action.key === 'password' ? (passwordOpen = true) : handleLogout()"
      >
        <component
          :is="action.icon"
          class="h-5 w-5"
        />
        <span class="flex-1 text-sm font-medium">{{ action.label }}</span>
        <ChevronRight
          v-if="action.key !== 'logout'"
          class="h-4 w-4 text-surface-400"
        />
      </button>
    </div>
  </div>

  <ProfileDialog
    v-model:open="profileOpen"
    v-model="profileForm"
    :profile="profile"
    :submitting="profileSubmitting"
    @submit="handleProfileSubmit"
  />
  <ChangePasswordDialog
    v-model:open="passwordOpen"
    :submitting="passwordSubmitting"
    @submit="handlePasswordSubmit"
  />
</template>
