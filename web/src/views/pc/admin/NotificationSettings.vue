<script setup lang="ts">
/**
 * 通知设置（PC 端，管理员）
 */
import { Mail } from '@lucide/vue'
import SmtpConfigCard from '@/components/notification/SmtpConfigCard.vue'
import NotificationEventSwitchCard from '@/components/notification/NotificationEventSwitchCard.vue'
import NotificationSendLogCard from '@/components/notification/NotificationSendLogCard.vue'
</script>

<template>
  <div>
    <div class="mb-6 flex items-center gap-3">
      <div class="rounded-xl bg-primary-50 p-2.5 text-primary-600">
        <Mail class="h-5 w-5" />
      </div>
      <div>
        <h2 class="text-lg font-bold text-surface-900">
          通知设置
        </h2>
        <p class="text-xs text-surface-500">
          邮件通知的发件邮箱配置、事件开关与发送记录
        </p>
      </div>
    </div>

    <div class="space-y-6">
      <SmtpConfigCard />
      <NotificationEventSwitchCard />
      <NotificationSendLogCard />
    </div>
  </div>
</template>

