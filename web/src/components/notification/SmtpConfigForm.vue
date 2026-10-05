<script setup lang="ts">
/**
 * SMTP 表单字段组件
 * - 管理端「发件邮箱」配置卡片与 PC/移动端系统初始化向导共用
 * - 只负责 7 个 SMTP 字段与加密方式选项；卡片外壳、校验与提交逻辑留在调用方
 * - v-model 绑定 SmtpFormModel 对象（字段就地更新，调用方传入 reactive/ref 对象即可）
 */
import { cn } from '@/utils/cn'
import type { SmtpEncryption, SmtpFormModel } from '@/types/notification'

interface Props {
  /** 密码框占位文案，管理端已配置时提示留空不修改 */
  passwordPlaceholder?: string
}

withDefaults(defineProps<Props>(), {
  passwordPlaceholder: 'SMTP 密码',
})

const model = defineModel<SmtpFormModel>({ required: true })

const encryptionOptions: { value: SmtpEncryption, label: string }[] = [
  { value: 'none', label: '无' },
  { value: 'ssl', label: 'SSL' },
  { value: 'starttls', label: 'STARTTLS' },
]

const labelClass = 'mb-1 block text-xs font-medium text-surface-500'
const inputClass = 'w-full rounded-xl border border-surface-200 bg-surface-50 px-4 py-2 text-sm outline-none focus:border-primary-300 focus:bg-white'
</script>

<template>
  <div class="space-y-3">
    <div class="grid gap-3 sm:grid-cols-3">
      <label class="sm:col-span-2">
        <span :class="labelClass">SMTP 主机</span>
        <input
          v-model="model.host"
          type="text"
          placeholder="smtp.example.com"
          :class="inputClass"
        >
      </label>
      <label>
        <span :class="labelClass">端口</span>
        <input
          v-model.number="model.port"
          type="number"
          min="1"
          max="65535"
          placeholder="465"
          :class="inputClass"
        >
      </label>
    </div>

    <div class="grid gap-3 sm:grid-cols-2">
      <label>
        <span :class="labelClass">账号（可选）</span>
        <input
          v-model="model.username"
          type="text"
          placeholder="user@example.com"
          :class="inputClass"
        >
      </label>
      <label>
        <span :class="labelClass">密码</span>
        <input
          v-model="model.password"
          type="password"
          :placeholder="passwordPlaceholder"
          autocomplete="new-password"
          :class="inputClass"
        >
      </label>
    </div>

    <div>
      <span :class="labelClass">加密方式</span>
      <div class="flex flex-wrap gap-2">
        <button
          v-for="option in encryptionOptions"
          :key="option.value"
          type="button"
          :class="cn(
            'rounded-xl border px-3 py-1.5 text-xs transition-colors sm:px-4 sm:py-2 sm:text-sm',
            model.encryption === option.value
              ? 'border-primary-300 bg-primary-50 font-semibold text-primary-700'
              : 'border-surface-200 bg-surface-50 text-surface-600 hover:border-primary-200',
          )"
          @click="model.encryption = option.value"
        >
          {{ option.label }}
        </button>
      </div>
    </div>

    <div class="grid gap-3 sm:grid-cols-2">
      <label>
        <span :class="labelClass">发件人地址</span>
        <input
          v-model="model.fromAddress"
          type="email"
          placeholder="no-reply@example.com"
          :class="inputClass"
        >
      </label>
      <label>
        <span :class="labelClass">发件人昵称（可选）</span>
        <input
          v-model="model.fromName"
          type="text"
          placeholder="jcloud"
          :class="inputClass"
        >
      </label>
    </div>
  </div>
</template>
