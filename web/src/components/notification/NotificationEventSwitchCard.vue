<script setup lang="ts">
/**
 * 通知事件开关卡片（管理员）：各事件逐一启用/禁用，禁用后不触发任何渠道
 */
import { onMounted, ref } from 'vue'
import { SwitchRoot, SwitchThumb } from 'radix-vue'
import { fetchNotificationEventSwitches, updateNotificationEventSwitch } from '@/api/notification'
import { useNotificationStore } from '@/store/notification'
import type { NotificationEventSwitch } from '@/types/notification'

const notificationStore = useNotificationStore()

const events = ref<NotificationEventSwitch[]>([])
const loading = ref(false)
const savingType = ref('')

onMounted(loadEvents)

async function loadEvents() {
  loading.value = true
  try {
    events.value = await fetchNotificationEventSwitches()
  } catch {
    // request.ts 已统一处理异常提示
  } finally {
    loading.value = false
  }
}

async function handleToggle(item: NotificationEventSwitch) {
  const target = !item.enabled
  item.enabled = target
  savingType.value = item.eventType
  try {
    await updateNotificationEventSwitch(item.eventType, target)
    notificationStore.success(`已${target ? '启用' : '禁用'}「${item.name}」`)
  } catch {
    item.enabled = !target
  } finally {
    savingType.value = ''
  }
}
</script>

<template>
  <div class="rounded-2xl border border-surface-100 bg-white p-6 shadow-soft">
    <h3 class="mb-1 text-base font-semibold text-surface-900">
      通知事件
    </h3>
    <p class="mb-4 text-xs text-surface-400">
      关闭某类事件后，站内通知与邮件均不再触发
    </p>

    <div
      v-if="loading"
      class="py-6 text-center text-sm text-surface-400"
    >
      加载中…
    </div>

    <ul
      v-else
      class="divide-y divide-surface-100"
    >
      <li
        v-for="item in events"
        :key="item.eventType"
        class="flex items-center justify-between gap-4 py-3"
      >
        <div>
          <p class="text-sm font-medium text-surface-800">
            {{ item.name }}
          </p>
          <p class="text-xs text-surface-400">
            {{ item.eventType }}
          </p>
        </div>
        <SwitchRoot
          :checked="item.enabled"
          :disabled="savingType === item.eventType"
          class="relative h-6 w-11 shrink-0 cursor-pointer rounded-full bg-surface-200 outline-none transition-colors data-[state=checked]:bg-primary-600 data-[disabled]:cursor-not-allowed data-[disabled]:opacity-50"
          @update:checked="handleToggle(item)"
        >
          <SwitchThumb
            class="block h-5 w-5 translate-x-0.5 rounded-full bg-white shadow-sm transition-transform data-[state=checked]:translate-x-[22px]"
          />
        </SwitchRoot>
      </li>
    </ul>
  </div>
</template>
