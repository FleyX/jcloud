<script setup lang="ts">
/**
 * 通知设置（移动端，管理员）
 */
import SmtpConfigCard from '@/components/notification/SmtpConfigCard.vue'
import NotificationEventSwitchCard from '@/components/notification/NotificationEventSwitchCard.vue'
import NotificationSendLogCard from '@/components/notification/NotificationSendLogCard.vue'
</script>

<template>
  <div class="space-y-4 p-4">
    <SmtpConfigCard />
    <NotificationEventSwitchCard />
    <NotificationSendLogCard />
  </div>
</template>
