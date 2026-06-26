<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { RoleVo, UserUpdateDto, UserVo } from '@/types/auth'
import type { StorageSpaceVo } from '@/types/storage-space'
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
import { bytesToUnitValue, unitValueToBytes, STORAGE_UNITS, type StorageUnit } from '@/utils/storage'

const props = withDefaults(defineProps<{
  open: boolean
  form: UserUpdateDto & { storageSpaceId?: string; quota?: string; quotaUnit?: string }
  editingUser: UserVo | null
  roles: RoleVo[]
  spaces?: StorageSpaceVo[]
  submitting: boolean
}>(), {
  spaces: () => [],
})

const emit = defineEmits<{
  'update:open': [value: boolean]
  submit: []
}>()

const localOpen = computed({
  get: () => props.open,
  set: (value) => emit('update:open', value),
})

const displayQuota = ref('10')
const displayUnit = ref<StorageUnit>('GB')
const isUnitInitializing = ref(false)

const currentSpaceName = computed(() => {
  if (!props.form.storageSpaceId) return '-'
  return props.spaces.find((s) => s.id === props.form.storageSpaceId)?.name || '-'
})

const isBuiltInAdmin = computed(() => props.editingUser?.username === 'admin')

watch(() => props.open, (open) => {
  if (open) {
    const unit = (props.form.quotaUnit || 'GB') as StorageUnit
    isUnitInitializing.value = true
    displayQuota.value = bytesToUnitValue(props.form.quota, unit)
    displayUnit.value = unit
    nextTick(() => {
      isUnitInitializing.value = false
    })
  }
})

watch(displayUnit, (newUnit, oldUnit) => {
  if (isUnitInitializing.value || !oldUnit) return
  displayQuota.value = bytesToUnitValue(unitValueToBytes(displayQuota.value, oldUnit), newUnit)
})

function handleSubmit() {
  props.form.quota = displayQuota.value
  props.form.quotaUnit = displayUnit.value
  emit('submit')
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
            <Settings2 class="h-5 w-5 text-primary-600" />
            <DialogTitle class="text-lg font-bold text-surface-900">
              编辑用户
            </DialogTitle>
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
            <label class="mb-1 block text-xs font-medium text-surface-700">角色</label>
            <div
              :class="cn(
                'space-y-2 rounded-xl border border-surface-200 p-3',
                isBuiltInAdmin && 'cursor-not-allowed opacity-60',
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
                  :disabled="isBuiltInAdmin"
                  class="h-4 w-4 rounded border-surface-300 text-primary-600 focus:ring-primary-500 disabled:cursor-not-allowed disabled:opacity-50"
                >
                <div class="flex-1">
                  <p class="text-sm font-medium text-surface-900">{{ role.name }}</p>
                  <p class="text-xs text-surface-500">{{ role.code }}</p>
                </div>
              </label>
            </div>
            <p
              v-if="isBuiltInAdmin"
              class="mt-1 text-xs text-surface-400"
            >
              admin 角色不可修改
            </p>
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">状态</label>
            <div class="flex items-center gap-3">
              <SwitchRoot
                :checked="props.form.status === 1"
                :disabled="isBuiltInAdmin"
                class="relative h-6 w-11 cursor-pointer rounded-full bg-surface-200 outline-none transition-colors data-[state=checked]:bg-primary-600 data-[disabled]:cursor-not-allowed data-[disabled]:opacity-50"
                @update:checked="(checked: boolean) => (props.form.status = checked ? 1 : 0)"
              >
                <SwitchThumb
                  class="block h-5 w-5 translate-x-0.5 rounded-full bg-white shadow-sm transition-transform data-[state=checked]:translate-x-[22px]"
                />
              </SwitchRoot>
              <span class="text-sm text-surface-700">{{ props.form.status === 1 ? '启用' : '禁用' }}</span>
            </div>
            <p
              v-if="isBuiltInAdmin"
              class="mt-1 text-xs text-surface-400"
            >
              admin 状态不可修改
            </p>
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">新密码（留空则不修改）</label>
            <input
              v-model="props.form.password"
              type="password"
              placeholder="请输入新密码"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">默认存储空间</label>
            <input
              :value="currentSpaceName"
              type="text"
              disabled
              class="w-full rounded-xl border border-surface-200 bg-surface-100 px-3 py-2 text-sm text-surface-500 outline-none disabled:cursor-not-allowed"
            >
            <p class="mt-1 text-xs text-surface-400">
              编辑用户时不可更换存储空间
            </p>
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">配额（0 表示无限）</label>
            <div class="flex gap-2">
              <input
                v-model="displayQuota"
                type="text"
                placeholder="请输入配额"
                class="flex-1 rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
              >
              <select
                v-model="displayUnit"
                class="rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
              >
                <option
                  v-for="unit in STORAGE_UNITS"
                  :key="unit"
                  :value="unit"
                >
                  {{ unit }}
                </option>
              </select>
            </div>
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
