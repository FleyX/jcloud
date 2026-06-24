<script setup lang="ts">
/**
 * 顶部一级导航栏
 * 负责全站核心模块切换，状态由 Pinia store/menu.ts 统一管理
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMenuStore, type PrimaryModule } from '@/store/menu'
import { useUserStore } from '@/store/user'
import { Cloud, Settings, LogOut, User, KeyRound } from '@lucide/vue'
import { cn } from '@/utils/cn'
import { getCurrentUserProfile, updateCurrentUserProfile, changePassword } from '@/api/user'
import type { ChangePasswordDto, UserProfileUpdateDto, UserProfileVo } from '@/types/auth'
import ProfileDialog from '@/components/user/ProfileDialog.vue'
import ChangePasswordDialog from '@/components/user/ChangePasswordDialog.vue'
import type { Component } from 'vue'

const router = useRouter()
const menuStore = useMenuStore()
const userStore = useUserStore()

interface PrimaryModuleItem {
  key: PrimaryModule
  label: string
  icon: Component
}

// notes / todos 模块尚未实现，暂不在一级导航中展示
const primaryModules: PrimaryModuleItem[] = [
  { key: 'files', label: '文件', icon: Cloud },
  { key: 'system', label: '系统', icon: Settings },
]

const availablePrimaryModules = computed(() =>
  primaryModules.filter((module) => menuStore.getSecondaryMenusByPrimary(module.key).length > 0),
)

function handleLogout() {
  userStore.logoutAction()
  router.push('/login')
}

function handlePrimaryClick(module: PrimaryModule) {
  menuStore.setPrimary(module)
  const targetRoute = menuStore.secondaryMenus[0]?.route
  if (targetRoute) {
    router.push(targetRoute)
  }
}

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
</script>

<template>
  <header
    class="flex h-14 shrink-0 items-center justify-between border-b border-surface-200 bg-white/80 px-5 backdrop-blur-md"
  >
    <!-- Logo -->
    <div class="flex items-center gap-2.5">
      <div
        class="flex h-9 w-9 items-center justify-center rounded-xl bg-primary-600 text-white shadow-soft"
      >
        <Cloud class="h-5 w-5" />
      </div>
      <span class="text-lg font-bold tracking-tight text-surface-900">JCloud</span>
    </div>

    <!-- 一级菜单 -->
    <nav class="flex items-center gap-1 rounded-2xl bg-surface-100 p-1">
      <button
        v-for="module in availablePrimaryModules"
        :key="module.key"
        :class="
          cn(
            'flex items-center gap-2 rounded-xl px-4 py-2 text-sm font-medium transition-all duration-200',
            menuStore.activePrimary === module.key
              ? 'bg-white text-primary-700 shadow-card'
              : 'text-surface-600 hover:bg-surface-200/50 hover:text-surface-900'
          )
        "
        @click="handlePrimaryClick(module.key)"
      >
        <component
          :is="module.icon"
          class="h-4 w-4"
        />
        {{ module.label }}
      </button>
    </nav>

    <!-- 右侧：用户菜单 -->
    <div class="flex items-center gap-3">
      <div class="group relative pb-2">
        <button
          class="flex h-9 w-9 items-center justify-center rounded-full bg-primary-100 text-primary-700 ring-2 ring-white transition-shadow hover:shadow-soft"
        >
          <span class="text-sm font-bold">{{ userStore.userInfo?.username?.charAt(0).toUpperCase() || 'U' }}</span>
        </button>
        <div
          class="invisible absolute right-0 top-full mt-2 w-44 origin-top-right scale-95 rounded-2xl border border-surface-200 bg-white p-1.5 opacity-0 shadow-card transition-all duration-200 delay-200 group-hover:visible group-hover:scale-100 group-hover:opacity-100 group-hover:delay-0"
        >
          <div class="border-b border-surface-100 px-3 py-2">
            <p class="text-sm font-semibold text-surface-900">
              {{ userStore.userInfo?.username || '未登录' }}
            </p>
            <p class="text-xs text-surface-500">
              {{ userStore.isAdmin ? '超级管理员' : '普通用户' }}
            </p>
          </div>
          <button
            class="flex w-full items-center gap-2 rounded-xl px-3 py-2 text-sm text-surface-700 transition-colors hover:bg-surface-100"
            @click="openProfile"
          >
            <User class="h-4 w-4" />
            个人信息
          </button>
          <button
            class="flex w-full items-center gap-2 rounded-xl px-3 py-2 text-sm text-surface-700 transition-colors hover:bg-surface-100"
            @click="passwordOpen = true"
          >
            <KeyRound class="h-4 w-4" />
            修改密码
          </button>
          <div class="my-1 border-b border-surface-100" />
          <button
            class="flex w-full items-center gap-2 rounded-xl px-3 py-2 text-sm text-red-600 transition-colors hover:bg-red-50"
            @click="handleLogout"
          >
            <LogOut class="h-4 w-4" />
            退出登录
          </button>
        </div>
      </div>
    </div>
  </header>

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
