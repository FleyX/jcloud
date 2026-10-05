<script setup lang="ts">
/**
 * 分享到邮件弹窗（链接分享 / 附件直发两种模式）
 * - 模式选择：链接分享 / 附件直发（仅选中集全为文件时可选，含文件夹不出现）
 * - 链接分享：先创建分享，再用站点 origin 组装分享链接调发送接口
 * - 附件直发：前端预检总大小上限，超限提示改用链接分享
 */
import { computed, ref, watch } from 'vue'
import { X, Mail, Link, Lock, Clock, Paperclip, AlertTriangle, Plus, Info } from '@lucide/vue'
import { cn } from '@/utils/cn'
import { isValidEmail } from '@/utils/email'
import { formatSize } from '@/utils/fileDisplay'
import { createShare, getRecentRecipients, sendEmailShareAttachment, sendEmailShareLink } from '@/api/share'
import { useNotificationStore } from '@/store/notification'
import type { FileNodeVo } from '@/types/file'

interface Props {
  open: boolean
  items: FileNodeVo[]
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
  sent: []
}>()

const notificationStore = useNotificationStore()

type ShareMode = 'link' | 'attachment'

const MAX_RECIPIENTS = 20

const mode = ref<ShareMode>('link')
const recipients = ref<string[]>([])
const recipientInput = ref('')
const recipientError = ref('')
const recentRecipients = ref<string[]>([])
const smtpConfigured = ref(true)
const attachmentMaxSizeMb = ref(50)
const shareName = ref('')
const hasPassword = ref(false)
const password = ref('')
const expireOption = ref<'1d' | '7d' | '30d' | 'never' | 'custom'>('7d')
const customExpireAt = ref('')
const submitting = ref(false)
const error = ref('')

const expireOptions: { value: typeof expireOption.value; label: string }[] = [
  { value: '1d', label: '1 天' },
  { value: '7d', label: '7 天' },
  { value: '30d', label: '30 天' },
  { value: 'never', label: '永久有效' },
]

const hasFolder = computed(() => props.items.some((item) => item.type === 'folder'))
const selectableRecent = computed(() => recentRecipients.value.filter((email) => !recipients.value.includes(email)))
const totalSelectedBytes = computed(() => props.items.reduce((sum, item) => sum + Number(item.size || 0), 0))
const attachmentMaxBytes = computed(() => attachmentMaxSizeMb.value * 1024 * 1024)
const sizeOverLimit = computed(() => mode.value === 'attachment' && totalSelectedBytes.value > attachmentMaxBytes.value)
const canSubmit = computed(() => {
  if (!smtpConfigured.value || submitting.value || recipients.value.length === 0) return false
  if (mode.value === 'attachment') return !sizeOverLimit.value
  return !!shareName.value.trim()
})
const submitLabel = computed(() => (mode.value === 'attachment' ? '发送附件' : '创建并发送'))

watch(
  () => props.open,
  (open) => {
    if (!open) return
    reset()
    if (props.items.length > 0) {
      shareName.value = props.items[0].name
    }
    loadRecentRecipients()
  },
)

function reset() {
  mode.value = 'link'
  recipients.value = []
  recipientInput.value = ''
  recipientError.value = ''
  recentRecipients.value = []
  smtpConfigured.value = true
  attachmentMaxSizeMb.value = 50
  shareName.value = ''
  hasPassword.value = false
  password.value = ''
  expireOption.value = '7d'
  customExpireAt.value = ''
  submitting.value = false
  error.value = ''
}

async function loadRecentRecipients() {
  try {
    const result = await getRecentRecipients()
    recentRecipients.value = result.recipients
    smtpConfigured.value = result.smtpConfigured
    attachmentMaxSizeMb.value = result.attachmentMaxSizeMb
  } catch {
    // 请求层已统一提示，此处保持可用状态交由提交时兜底
  }
}

function handleRecipientInput(raw: string) {
  raw
    .split(/[,，;；\s]+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
    .forEach(addRecipient)
  recipientInput.value = ''
}

function addRecipient(email: string) {
  if (!isValidEmail(email)) {
    recipientError.value = `邮箱格式不正确：${email}`
    return
  }
  if (recipients.value.includes(email)) {
    recipientError.value = ''
    return
  }
  if (recipients.value.length >= MAX_RECIPIENTS) {
    recipientError.value = `最多添加 ${MAX_RECIPIENTS} 个收件人`
    return
  }
  recipients.value.push(email)
  recipientError.value = ''
}

function removeRecipient(email: string) {
  recipients.value = recipients.value.filter((item) => item !== email)
}

function buildExpireAt(): string | undefined {
  if (expireOption.value === 'never') return undefined
  if (expireOption.value === 'custom') {
    return customExpireAt.value ? new Date(customExpireAt.value).toISOString() : undefined
  }
  const now = new Date()
  const days = expireOption.value === '1d' ? 1 : expireOption.value === '7d' ? 7 : 30
  now.setDate(now.getDate() + days)
  return now.toISOString()
}

function validate(): boolean {
  if (recipients.value.length === 0) {
    error.value = '请至少添加一个收件邮箱'
    return false
  }
  if (mode.value === 'attachment') {
    if (sizeOverLimit.value) {
      error.value = `所选文件超过附件大小上限 ${attachmentMaxSizeMb.value}MB，请改用链接分享`
      return false
    }
    error.value = ''
    return true
  }
  if (!shareName.value.trim()) {
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

async function handleSubmit() {
  if (!validate()) return
  submitting.value = true
  try {
    if (mode.value === 'attachment') {
      await sendEmailShareAttachment({
        fileNodeIds: props.items.map((item) => item.id),
        recipients: recipients.value,
      })
      notificationStore.success('邮件发送中，结果将通知你')
    } else {
      const accessPassword = hasPassword.value ? password.value.trim() : undefined
      const share = await createShare({
        name: shareName.value.trim(),
        fileNodeIds: props.items.map((item) => item.id),
        password: accessPassword,
        expireAt: buildExpireAt(),
      })
      await sendEmailShareLink({
        shareCode: share.shareCode,
        shareUrl: `${window.location.origin}/s/${share.shareCode}`,
        shareName: shareName.value.trim(),
        password: accessPassword,
        recipients: recipients.value,
      })
      notificationStore.success('分享已创建，邮件发送中')
    }
    emit('sent')
    emit('close')
  } catch {
    // 请求层已统一提示
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
    @click.self="emit('close')"
  >
    <div class="max-h-[90vh] w-full max-w-md overflow-y-auto rounded-3xl border border-surface-200 bg-white p-6 shadow-soft">
      <div class="mb-5 flex items-center gap-3">
        <div class="flex h-10 w-10 items-center justify-center rounded-xl bg-primary-100 text-primary-600">
          <Mail class="h-5 w-5" />
        </div>
        <h3 class="text-lg font-semibold text-surface-900">
          分享到邮件
        </h3>
        <button
          class="ml-auto rounded-lg p-1 text-surface-400 hover:bg-surface-100"
          @click="emit('close')"
        >
          <X class="h-4 w-4" />
        </button>
      </div>

      <div
        v-if="!smtpConfigured"
        class="mb-4 flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 px-3 py-2.5 text-xs text-amber-700"
      >
        <AlertTriangle class="mt-0.5 h-4 w-4 shrink-0" />
        <span>系统尚未配置发件邮箱，邮件分享不可用，请联系管理员在「通知」菜单中配置 SMTP。</span>
      </div>

      <div class="space-y-4">
        <div>
          <label class="mb-1.5 block text-xs font-medium text-surface-500">分享方式</label>
          <div class="grid grid-cols-2 gap-2">
            <button
              :class="
                cn(
                  'flex items-center justify-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-medium transition-all',
                  mode === 'link'
                    ? 'border-primary-300 bg-primary-50 text-primary-700'
                    : 'border-surface-200 bg-white text-surface-600 hover:bg-surface-50'
                )
              "
              @click="mode = 'link'"
            >
              <Link class="h-3.5 w-3.5" />
              链接分享
            </button>
            <button
              v-if="!hasFolder"
              :class="
                cn(
                  'flex items-center justify-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-medium transition-all',
                  mode === 'attachment'
                    ? 'border-primary-300 bg-primary-50 text-primary-700'
                    : 'border-surface-200 bg-white text-surface-600 hover:bg-surface-50'
                )
              "
              @click="mode = 'attachment'"
            >
              <Paperclip class="h-3.5 w-3.5" />
              附件直发（≤{{ attachmentMaxSizeMb }}MB）
            </button>
          </div>
          <p
            v-if="mode === 'link'"
            class="mt-1.5 text-xs text-surface-400"
          >
            创建分享后，链接与访问密码将发送到收件邮箱。
          </p>
          <p
            v-else
            class="mt-1.5 text-xs text-surface-400"
          >
            文件将作为邮件附件直接发送。
          </p>
        </div>

        <div
          v-if="mode === 'attachment'"
          class="space-y-2 rounded-xl border border-surface-200 bg-surface-50 px-3 py-2.5"
        >
          <div class="flex items-center justify-between text-xs">
            <span class="text-surface-500">所选文件总大小</span>
            <span :class="cn('font-medium', sizeOverLimit ? 'text-red-500' : 'text-surface-700')">
              {{ formatSize(totalSelectedBytes) }} / {{ attachmentMaxSizeMb }}MB
            </span>
          </div>
          <p
            v-if="sizeOverLimit"
            class="text-xs text-red-500"
          >
            超过附件大小上限，请改用链接分享。
          </p>
          <p class="flex items-start gap-1.5 text-xs text-surface-400">
            <Info class="mt-0.5 h-3.5 w-3.5 shrink-0" />
            <span>发送至 Kindle 时，需先将发件邮箱加入 Kindle 认可邮箱白名单。</span>
          </p>
        </div>

        <div>
          <label class="mb-1.5 block text-xs font-medium text-surface-500">收件人（最多 20 个）</label>
          <div class="flex flex-wrap gap-1.5 rounded-xl border border-surface-200 bg-surface-50 px-3 py-2">
            <span
              v-for="email in recipients"
              :key="email"
              class="flex items-center gap-1 rounded-lg bg-primary-50 px-2 py-1 text-xs text-primary-700"
            >
              {{ email }}
              <button
                class="text-primary-400 hover:text-primary-700"
                @click="removeRecipient(email)"
              >
                <X class="h-3 w-3" />
              </button>
            </span>
            <input
              v-model="recipientInput"
              type="text"
              placeholder="输入邮箱后回车添加"
              class="min-w-[8rem] flex-1 bg-transparent py-1 text-sm outline-none placeholder:text-surface-400"
              @keyup.enter="handleRecipientInput(recipientInput)"
              @blur="handleRecipientInput(recipientInput)"
            >
          </div>
          <p
            v-if="recipientError"
            class="mt-1.5 text-xs text-red-500"
          >
            {{ recipientError }}
          </p>
          <div
            v-if="selectableRecent.length > 0"
            class="mt-2 flex flex-wrap items-center gap-1.5"
          >
            <span class="text-xs text-surface-400">最近使用：</span>
            <button
              v-for="email in selectableRecent"
              :key="email"
              class="flex items-center gap-1 rounded-lg border border-surface-200 px-2 py-1 text-xs text-surface-600 hover:bg-surface-50"
              @click="addRecipient(email)"
            >
              <Plus class="h-3 w-3" />
              {{ email }}
            </button>
          </div>
        </div>

        <div v-if="mode === 'link'">
          <label class="mb-1.5 block text-xs font-medium text-surface-500">分享名称</label>
          <input
            v-model="shareName"
            type="text"
            placeholder="给分享起个名字"
            class="w-full rounded-xl border border-surface-200 bg-surface-50 px-4 py-2.5 text-sm outline-none transition-all focus:border-primary-300 focus:bg-white focus:ring-2 focus:ring-primary-100"
          >
        </div>

        <div
          v-if="mode === 'link'"
          class="flex items-center justify-between rounded-xl border border-surface-200 bg-surface-50 px-4 py-3"
        >
          <div class="flex items-center gap-2 text-sm font-medium text-surface-700">
            <Lock class="h-4 w-4 text-surface-500" />
            访问密码
          </div>
          <button
            :class="cn('relative h-6 w-11 rounded-full transition-colors', hasPassword ? 'bg-primary-600' : 'bg-surface-300')"
            @click="hasPassword = !hasPassword"
          >
            <span
              :class="
                cn(
                  'absolute top-1 left-1 h-4 w-4 rounded-full bg-white transition-transform',
                  hasPassword ? 'translate-x-5' : 'translate-x-0'
                )
              "
            />
          </button>
        </div>
        <div v-if="mode === 'link' && hasPassword">
          <input
            v-model="password"
            type="text"
            placeholder="设置访问密码"
            maxlength="16"
            class="w-full rounded-xl border border-surface-200 bg-surface-50 px-4 py-2.5 text-sm outline-none transition-all focus:border-primary-300 focus:bg-white focus:ring-2 focus:ring-primary-100"
          >
        </div>

        <div v-if="mode === 'link'">
          <label class="mb-1.5 flex items-center gap-2 text-xs font-medium text-surface-500">
            <Clock class="h-3.5 w-3.5" />
            有效期
          </label>
          <div class="grid grid-cols-5 gap-2">
            <button
              v-for="opt in expireOptions"
              :key="opt.value"
              :class="
                cn(
                  'rounded-xl border px-1 py-2 text-xs font-medium transition-all',
                  expireOption === opt.value
                    ? 'border-primary-300 bg-primary-50 text-primary-700'
                    : 'border-surface-200 bg-white text-surface-600 hover:bg-surface-50'
                )
              "
              @click="expireOption = opt.value"
            >
              {{ opt.label }}
            </button>
            <button
              :class="
                cn(
                  'rounded-xl border px-1 py-2 text-xs font-medium transition-all',
                  expireOption === 'custom'
                    ? 'border-primary-300 bg-primary-50 text-primary-700'
                    : 'border-surface-200 bg-white text-surface-600 hover:bg-surface-50'
                )
              "
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
          @click="emit('close')"
        >
          取消
        </button>
        <button
          class="rounded-xl bg-primary-600 px-4 py-2 text-sm font-semibold text-white shadow-soft transition-all hover:bg-primary-700 hover:shadow-card active:scale-95 disabled:cursor-not-allowed disabled:bg-surface-300"
          :disabled="!canSubmit"
          @click="handleSubmit"
        >
          {{ submitting ? '发送中...' : submitLabel }}
        </button>
      </div>
    </div>
  </div>
</template>
