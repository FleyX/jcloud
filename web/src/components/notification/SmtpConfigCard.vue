<script setup lang="ts">
/**
 * 发件邮箱（SMTP）配置卡片（管理员）
 */
import { computed, onMounted, ref } from 'vue'
import { getSmtpConfig, sendTestMail, updateSmtpConfig } from '@/api/notification'
import { useNotificationStore } from '@/store/notification'
import { cn } from '@/utils/cn'
import type { SmtpConfigPayload, SmtpEncryption } from '@/types/notification'

const notificationStore = useNotificationStore()

const encryptionOptions: { value: SmtpEncryption, label: string }[] = [
  { value: 'none', label: '无' },
  { value: 'ssl', label: 'SSL' },
  { value: 'starttls', label: 'STARTTLS' },
]

const inputClass = 'w-full rounded-xl border border-surface-200 bg-surface-50 px-4 py-2 text-sm outline-none focus:border-primary-300 focus:bg-white'

const host = ref('')
const port = ref(465)
const username = ref('')
const password = ref('')
const encryption = ref<SmtpEncryption>('ssl')
const fromAddress = ref('')
const fromName = ref('')
const hasPassword = ref(false)
const configured = ref(false)
const loading = ref(false)
const saving = ref(false)
const testTo = ref('')
const testing = ref(false)

const passwordPlaceholder = computed(() => (hasPassword.value ? '已设置，留空不修改' : 'SMTP 密码'))
const testDisabled = computed(() => testing.value || testTo.value.trim() === '')

onMounted(loadConfig)

async function loadConfig() {
  loading.value = true
  try {
    const config = await getSmtpConfig()
    host.value = config.host
    port.value = config.port
    username.value = config.username
    password.value = ''
    encryption.value = config.encryption
    fromAddress.value = config.fromAddress
    fromName.value = config.fromName
    hasPassword.value = config.hasPassword
    configured.value = config.host !== '' && config.fromAddress !== ''
  } catch {
    // request.ts 已统一处理异常提示
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  saving.value = true
  try {
    const payload: SmtpConfigPayload = {
      host: host.value.trim(),
      port: Number(port.value),
      username: username.value.trim(),
      encryption: encryption.value,
      fromAddress: fromAddress.value.trim(),
      fromName: fromName.value.trim(),
    }
    if (password.value !== '') {
      payload.password = password.value
    }
    await updateSmtpConfig(payload)
    notificationStore.success('发件邮箱配置已保存')
    await loadConfig()
  } catch {
    // request.ts 已统一处理异常提示
  } finally {
    saving.value = false
  }
}

async function handleTestSend() {
  testing.value = true
  try {
    await sendTestMail(testTo.value.trim())
    notificationStore.success('测试邮件已发送，请查收')
  } catch {
    // request.ts 已统一处理异常提示
  } finally {
    testing.value = false
  }
}
</script>

<template>
  <div class="rounded-2xl border border-surface-100 bg-white p-6 shadow-soft">
    <h3 class="mb-1 text-base font-semibold text-surface-900">
      发件邮箱
    </h3>
    <p class="mb-4 text-xs text-surface-400">
      全局唯一的邮件发件配置，保存后由通知功能统一使用
    </p>

    <div
      v-if="!loading && !configured"
      class="mb-5 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-700"
    >
      未配置发件邮箱，通知功能不可用。请填写并保存下方配置。
    </div>

    <div class="space-y-4">
      <div class="flex flex-wrap gap-4">
        <label class="flex-1 min-w-64">
          <span class="mb-1 block text-xs font-medium text-surface-500">SMTP 主机</span>
          <input
            v-model="host"
            type="text"
            placeholder="smtp.example.com"
            :class="inputClass"
          >
        </label>
        <label class="w-40">
          <span class="mb-1 block text-xs font-medium text-surface-500">端口</span>
          <input
            v-model.number="port"
            type="number"
            min="1"
            max="65535"
            placeholder="465"
            :class="inputClass"
          >
        </label>
      </div>

      <div class="flex flex-wrap gap-4">
        <label class="flex-1 min-w-64">
          <span class="mb-1 block text-xs font-medium text-surface-500">账号（可选）</span>
          <input
            v-model="username"
            type="text"
            placeholder="user@example.com"
            :class="inputClass"
          >
        </label>
        <label class="flex-1 min-w-64">
          <span class="mb-1 block text-xs font-medium text-surface-500">密码</span>
          <input
            v-model="password"
            type="password"
            :placeholder="passwordPlaceholder"
            autocomplete="new-password"
            :class="inputClass"
          >
        </label>
      </div>

      <div>
        <span class="mb-1 block text-xs font-medium text-surface-500">加密方式</span>
        <div class="flex flex-wrap gap-2">
          <button
            v-for="option in encryptionOptions"
            :key="option.value"
            type="button"
            :class="cn(
              'rounded-xl border px-4 py-2 text-sm transition-colors',
              encryption === option.value
                ? 'border-primary-300 bg-primary-50 font-semibold text-primary-700'
                : 'border-surface-200 bg-surface-50 text-surface-600 hover:border-primary-200',
            )"
            @click="encryption = option.value"
          >
            {{ option.label }}
          </button>
        </div>
      </div>

      <div class="flex flex-wrap gap-4">
        <label class="flex-1 min-w-64">
          <span class="mb-1 block text-xs font-medium text-surface-500">发件人地址</span>
          <input
            v-model="fromAddress"
            type="email"
            placeholder="no-reply@example.com"
            :class="inputClass"
          >
        </label>
        <label class="flex-1 min-w-64">
          <span class="mb-1 block text-xs font-medium text-surface-500">发件人昵称（可选）</span>
          <input
            v-model="fromName"
            type="text"
            placeholder="jcloud"
            :class="inputClass"
          >
        </label>
      </div>

      <div class="flex justify-end">
        <button
          class="rounded-xl bg-primary-600 px-5 py-2 text-sm font-semibold text-white hover:bg-primary-700 disabled:bg-surface-300"
          :disabled="saving || loading"
          @click="handleSave"
        >
          保存
        </button>
      </div>
    </div>

    <div class="mt-6 border-t border-surface-100 pt-5">
      <h4 class="mb-1 text-sm font-semibold text-surface-800">
        发送测试邮件
      </h4>
      <p class="mb-3 text-xs text-surface-400">
        保存配置后填写收件地址验证，配置错误时会返回具体提示
      </p>
      <div class="flex flex-wrap items-center gap-3">
        <input
          v-model="testTo"
          type="email"
          placeholder="收件人邮箱"
          class="w-72 max-w-full rounded-xl border border-surface-200 bg-surface-50 px-4 py-2 text-sm outline-none focus:border-primary-300 focus:bg-white"
        >
        <button
          class="rounded-xl border border-primary-200 bg-primary-50 px-5 py-2 text-sm font-semibold text-primary-700 hover:bg-primary-100 disabled:border-surface-200 disabled:bg-surface-100 disabled:text-surface-400"
          :disabled="testDisabled"
          @click="handleTestSend"
        >
          发送测试邮件
        </button>
      </div>
    </div>
  </div>
</template>
