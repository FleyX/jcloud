<script setup lang="ts">
/**
 * 创建/编辑分享弹窗
 */
import { ref, watch, computed } from 'vue'
import { X, Link, Lock, Clock, Eye, File, Folder } from '@lucide/vue'
import { cn } from '@/utils/cn'
import type { ShareCreateRequest, ShareUpdateRequest, ShareDetailVo } from '@/types/share'

interface Props {
  open: boolean
  itemIds: string[]
  editShare?: ShareDetailVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
  confirm: [payload: ShareCreateRequest | ShareUpdateRequest]
}>()

const name = ref('')
const description = ref('')
const hasPassword = ref(false)
const password = ref('')
const expireOption = ref<'1d' | '7d' | '30d' | 'never' | 'custom'>('7d')
const customExpireAt = ref('')
const maxViews = ref<number | undefined>(undefined)
const error = ref('')

const isEdit = computed(() => !!props.editShare)
const title = computed(() => (isEdit.value ? '编辑分享' : '创建分享'))

const expireOptions: { value: typeof expireOption.value; label: string }[] = [
  { value: '1d', label: '1 天' },
  { value: '7d', label: '7 天' },
  { value: '30d', label: '30 天' },
  { value: 'never', label: '永久有效' },
  { value: 'custom', label: '自定义' },
]

function reset() {
  name.value = ''
  description.value = ''
  hasPassword.value = false
  password.value = ''
  expireOption.value = '7d'
  customExpireAt.value = ''
  maxViews.value = undefined
  error.value = ''
}

function fillEdit() {
  const share = props.editShare
  if (!share) {
    reset()
    return
  }
  name.value = share.name
  description.value = share.description ?? ''
  hasPassword.value = share.hasPassword
  password.value = ''
  maxViews.value = share.maxViews ? Number(share.maxViews) : undefined

  if (!share.expireAt) {
    expireOption.value = 'never'
    customExpireAt.value = ''
    return
  }
  const expire = new Date(share.expireAt)
  const now = new Date()
  const diffHours = (expire.getTime() - now.getTime()) / 3600000
  const matched = expireOptions.find((opt) => {
    if (opt.value === '1d') return Math.abs(diffHours - 24) < 1
    if (opt.value === '7d') return Math.abs(diffHours - 168) < 1
    if (opt.value === '30d') return Math.abs(diffHours - 720) < 1
    return false
  })
  if (matched) {
    expireOption.value = matched.value
    customExpireAt.value = ''
  } else {
    expireOption.value = 'custom'
    customExpireAt.value = formatDatetimeLocal(expire)
  }
}

function formatDatetimeLocal(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      if (isEdit.value) {
        fillEdit()
      } else {
        reset()
        name.value = `我的分享 ${new Date().toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}`
      }
    }
  },
)

function buildExpireAt(): string | undefined {
  if (expireOption.value === 'never') return undefined
  if (expireOption.value === 'custom') return customExpireAt.value ? new Date(customExpireAt.value).toISOString() : undefined
  const now = new Date()
  const days = expireOption.value === '1d' ? 1 : expireOption.value === '7d' ? 7 : 30
  now.setDate(now.getDate() + days)
  return now.toISOString()
}

function validate(): boolean {
  const trimmedName = name.value.trim()
  if (!trimmedName) {
    error.value = '分享名称不能为空'
    return false
  }
  if (hasPassword.value && !password.value.trim()) {
    error.value = '请输入访问密码'
    return false
  }
  if (expireOption.value === 'custom' && !customExpireAt.value) {
    error.value = '请选择自定义过期时间'
    return false
  }
  error.value = ''
  return true
}

function handleConfirm() {
  if (!validate()) return
  const base = {
    name: name.value.trim(),
    description: description.value.trim() || undefined,
    password: hasPassword.value ? password.value.trim() : undefined,
    expireAt: buildExpireAt(),
    maxViews: maxViews.value,
  }
  if (isEdit.value && props.editShare) {
    const payload: ShareUpdateRequest = {
      ...base,
      fileNodeIds: props.editShare.items.map((item) => item.fileNodeId),
      status: props.editShare.status,
    }
    emit('confirm', payload)
  } else {
    const payload: ShareCreateRequest = {
      ...base,
      fileNodeIds: props.itemIds,
    }
    emit('confirm', payload)
  }
}

function handleClose() {
  emit('close')
}
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
    @click.self="handleClose"
  >
    <div class="w-full max-w-md rounded-3xl border border-surface-200 bg-white p-6 shadow-soft max-h-[90vh] overflow-y-auto">
      <div class="mb-5 flex items-center gap-3">
        <div class="flex h-10 w-10 items-center justify-center rounded-xl bg-primary-100 text-primary-600">
          <Link class="h-5 w-5" />
        </div>
        <h3 class="text-lg font-semibold text-surface-900">
          {{ title }}
        </h3>
        <button
          class="ml-auto rounded-lg p-1 text-surface-400 hover:bg-surface-100"
          @click="handleClose"
        >
          <X class="h-4 w-4" />
        </button>
      </div>

      <div class="space-y-4">
        <div>
          <label class="mb-1.5 block text-xs font-medium text-surface-500">分享名称</label>
          <input
            v-model="name"
            type="text"
            placeholder="给分享起个名字"
            class="w-full rounded-xl border border-surface-200 bg-surface-50 px-4 py-2.5 text-sm outline-none transition-all focus:border-primary-300 focus:bg-white focus:ring-2 focus:ring-primary-100"
          >
        </div>

        <div>
          <label class="mb-1.5 block text-xs font-medium text-surface-500">描述（可选）</label>
          <textarea
            v-model="description"
            rows="2"
            placeholder="添加一段描述"
            class="w-full resize-none rounded-xl border border-surface-200 bg-surface-50 px-4 py-2.5 text-sm outline-none transition-all focus:border-primary-300 focus:bg-white focus:ring-2 focus:ring-primary-100"
          />
        </div>

        <div>
          <label class="mb-1.5 flex items-center gap-2 text-xs font-medium text-surface-500">
            <File class="h-3.5 w-3.5" />
            分享内容
          </label>
          <div
            v-if="isEdit && editShare"
            class="rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm text-surface-700"
          >
            <div class="max-h-24 space-y-1 overflow-y-auto">
              <div
                v-for="item in editShare.items"
                :key="item.id"
                class="flex items-center gap-2"
              >
                <Folder
                  v-if="item.type === 'folder'"
                  class="h-4 w-4 text-amber-500"
                />
                <File
                  v-else
                  class="h-4 w-4 text-primary-500"
                />
                <span class="truncate">{{ item.name }}</span>
              </div>
            </div>
          </div>
          <div
            v-else
            class="rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm text-surface-700"
          >
            已选择 {{ itemIds.length }} 个项目
          </div>
        </div>

        <div class="flex items-center justify-between rounded-xl border border-surface-200 bg-surface-50 px-4 py-3">
          <div class="flex items-center gap-2 text-sm font-medium text-surface-700">
            <Lock class="h-4 w-4 text-surface-500" />
            访问密码
          </div>
          <button
            :class="cn(
              'relative h-6 w-11 rounded-full transition-colors',
              hasPassword ? 'bg-primary-600' : 'bg-surface-300'
            )"
            @click="hasPassword = !hasPassword"
          >
            <span
              :class="cn(
                'absolute top-1 left-1 h-4 w-4 rounded-full bg-white transition-transform',
                hasPassword ? 'translate-x-5' : 'translate-x-0'
              )"
            />
          </button>
        </div>
        <div v-if="hasPassword">
          <input
            v-model="password"
            type="text"
            placeholder="设置访问密码"
            maxlength="16"
            class="w-full rounded-xl border border-surface-200 bg-surface-50 px-4 py-2.5 text-sm outline-none transition-all focus:border-primary-300 focus:bg-white focus:ring-2 focus:ring-primary-100"
          >
        </div>

        <div>
          <label class="mb-1.5 flex items-center gap-2 text-xs font-medium text-surface-500">
            <Clock class="h-3.5 w-3.5" />
            有效期
          </label>
          <div class="grid grid-cols-3 gap-2">
            <button
              v-for="opt in expireOptions.filter(o => o.value !== 'custom')"
              :key="opt.value"
              :class="cn(
                'rounded-xl border px-2 py-2 text-xs font-medium transition-all',
                expireOption === opt.value
                  ? 'border-primary-300 bg-primary-50 text-primary-700'
                  : 'border-surface-200 bg-white text-surface-600 hover:bg-surface-50'
              )"
              @click="expireOption = opt.value"
            >
              {{ opt.label }}
            </button>
            <button
              :class="cn(
                'rounded-xl border px-2 py-2 text-xs font-medium transition-all',
                expireOption === 'custom'
                  ? 'border-primary-300 bg-primary-50 text-primary-700'
                  : 'border-surface-200 bg-white text-surface-600 hover:bg-surface-50'
              )"
              @click="expireOption = 'custom'"
            >
              自定义
            </button>
          </div>
          <input
            v-if="expireOption === 'custom'"
            v-model="customExpireAt"
            type="datetime-local"
            class="mt-2 w-full rounded-xl border border-surface-200 bg-surface-50 px-4 py-2.5 text-sm outline-none transition-all focus:border-primary-300 focus:bg-white focus:ring-2 focus:ring-primary-100"
          >
        </div>

        <div>
          <label class="mb-1.5 flex items-center gap-2 text-xs font-medium text-surface-500">
            <Eye class="h-3.5 w-3.5" />
            最大访问次数（可选）
          </label>
          <input
            v-model.number="maxViews"
            type="number"
            min="1"
            placeholder="不限制"
            class="w-full rounded-xl border border-surface-200 bg-surface-50 px-4 py-2.5 text-sm outline-none transition-all focus:border-primary-300 focus:bg-white focus:ring-2 focus:ring-primary-100"
          >
        </div>
      </div>

      <p
        v-if="error"
        class="mt-3 text-xs text-red-500"
      >
        {{ error }}
      </p>

      <div class="mt-5 flex justify-end gap-2">
        <button
          class="rounded-xl px-4 py-2 text-sm font-medium text-surface-600 transition-colors hover:bg-surface-100"
          @click="handleClose"
        >
          取消
        </button>
        <button
          class="rounded-xl bg-primary-600 px-4 py-2 text-sm font-semibold text-white shadow-soft transition-all hover:bg-primary-700 hover:shadow-card active:scale-95 disabled:cursor-not-allowed disabled:bg-surface-300"
          :disabled="!name.trim()"
          @click="handleConfirm"
        >
          {{ isEdit ? '保存' : '创建' }}
        </button>
      </div>
    </div>
  </div>
</template>
