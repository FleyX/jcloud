<script setup lang="ts">
import { computed } from 'vue'
import type { RoleVo, UserUpdateDto, UserVo } from '@/types/auth'
import { Settings2, X } from '@lucide/vue'
import {
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
  DialogTitle,
  SwitchRoot,
  SwitchThumb,
} from 'radix-vue'
import { cn } from '@/utils/cn'

const props = defineProps<{
  open: boolean
  form: UserUpdateDto
  editingUser: UserVo | null
  roles: RoleVo[]
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
            <Settings2 class="h-5 w-5 text-primary-600" />
            <DialogTitle class="text-lg font-bold text-surface-900">编辑用户</DialogTitle>
          </div>
          <DialogClose
            class="rounded-lg p-1 text-surface-400 outline-none hover:bg-surface-100 hover:text-surface-600"
          >
            <X class="h-5 w-5" />
          </DialogClose>
        </div>
        <DialogDescription class="mb-4 text-sm text-surface-500">
          编辑用户 <span class="font-semibold text-surface-700">{{ editingUser?.username }}</span> 的信息
        </DialogDescription>

        <div class="mb-6 max-h-[60vh] space-y-4 overflow-y-auto pr-1">
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">昵称</label>
            <input
              v-model="props.form.nickname"
              type="text"
              :disabled="editingUser?.isAdmin"
              placeholder="请输入昵称"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100 disabled:cursor-not-allowed disabled:opacity-60"
            />
            <p v-if="editingUser?.isAdmin" class="mt-1 text-xs text-surface-400">超级管理员昵称不可修改</p>
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">邮箱</label>
            <input
              v-model="props.form.email"
              type="email"
              placeholder="请输入邮箱"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            />
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">角色</label>
            <div
              :class="cn(
                'space-y-2 rounded-xl border border-surface-200 p-3',
                editingUser?.isAdmin && 'cursor-not-allowed opacity-60',
              )"
            >
              <label
                v-for="role in roles"
                :key="role.id"
                class="flex cursor-pointer items-center gap-3 rounded-lg p-2 transition-colors hover:bg-surface-50"
              >
                <input
                  v-model="props.form.roleIds"
                  type="checkbox"
                  :value="role.id"
                  :disabled="editingUser?.isAdmin"
                  class="h-4 w-4 rounded border-surface-300 text-primary-600 focus:ring-primary-500 disabled:cursor-not-allowed disabled:opacity-50"
                />
                <div class="flex-1">
                  <p class="text-sm font-medium text-surface-900">{{ role.name }}</p>
                  <p class="text-xs text-surface-500">{{ role.code }}</p>
                </div>
              </label>
            </div>
            <p v-if="editingUser?.isAdmin" class="mt-1 text-xs text-surface-400">超级管理员角色不可修改</p>
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">状态</label>
            <div class="flex items-center gap-3">
              <SwitchRoot
                :checked="props.form.status === 1"
                :disabled="editingUser?.isAdmin"
                class="relative h-6 w-11 cursor-pointer rounded-full bg-surface-200 outline-none transition-colors data-[state=checked]:bg-primary-600 data-[disabled]:cursor-not-allowed data-[disabled]:opacity-50"
                @update:checked="(checked: boolean) => (props.form.status = checked ? 1 : 0)"
              >
                <SwitchThumb
                  class="block h-5 w-5 translate-x-0.5 rounded-full bg-white shadow-sm transition-transform data-[state=checked]:translate-x-[22px]"
                />
              </SwitchRoot>
              <span class="text-sm text-surface-700">{{ props.form.status === 1 ? '启用' : '禁用' }}</span>
            </div>
            <p v-if="editingUser?.isAdmin" class="mt-1 text-xs text-surface-400">超级管理员状态不可修改</p>
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">新密码（留空则不修改）</label>
            <input
              v-model="props.form.password"
              type="password"
              placeholder="请输入新密码"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            />
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
