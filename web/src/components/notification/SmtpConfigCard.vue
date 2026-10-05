<script setup lang="ts">
/**
 * 发件邮箱（SMTP）配置卡片（管理员）
 */
import { computed, onMounted, ref } from 'vue'
import { getSmtpConfig, sendTestMail, updateSmtpConfig } from '@/api/notification'
import { useNotificationStore } from '@/store/notification'
import SmtpConfigForm from './SmtpConfigForm.vue'
import type { SmtpConfigPayload, SmtpFormModel } from '@/types/notification'

const notificationStore = useNotificationStore()

const inputClass = 'w-full rounded-xl border border-surface-200 bg-surface-50 px-4 py-2 text-sm outline-none focus:border-primary-300 focus:bg-white'

const form = ref<SmtpFormModel>({
  host: '',
  port: 465,
  username: '',
  password: '',
  encryption: 'ssl',
  fromAddress: '',
  fromName: '',
})
const attachmentMaxSizeMb = ref(50)
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
    form.value.host = config.host
    form.value.port = config.port
    form.value.username = config.username
    form.value.password = ''
    form.value.encryption = config.encryption
    form.value.fromAddress = config.fromAddress
    form.value.fromName = config.fromName
    attachmentMaxSizeMb.value = config.attachmentMaxSizeMb
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
      host: form.value.host.trim(),
      port: Number(form.value.port),
      username: form.value.username.trim(),
      encryption: form.value.encryption,
      fromAddress: form.value.fromAddress.trim(),
      fromName: form.value.fromName.trim(),
      attachmentMaxSizeMb: Number(attachmentMaxSizeMb.value),
    }
    if (form.value.password !== '') {
      payload.password = form.value.password
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
      <SmtpConfigForm
        v-model="form"
        :password-placeholder="passwordPlaceholder"
      />

      <label class="block w-64 max-w-full">
        <span class="mb-1 block text-xs font-medium text-surface-500">附件大小上限（MB）</span>
        <input
          v-model.number="attachmentMaxSizeMb"
          type="number"
          min="1"
          max="1024"
          placeholder="50"
          :class="inputClass"
        >
        <span class="mt-1 block text-xs text-surface-400">邮件分享附件直发的总大小上限，超出时提示改用链接分享</span>
      </label>

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
