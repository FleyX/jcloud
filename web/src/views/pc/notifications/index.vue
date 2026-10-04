<script setup lang="ts">
/**
 * 通知页（PC）
 * 展示当前用户最近通知（最多 50 条），支持单条已读、全部已读与空态。
 */
import { computed, onMounted, ref } from 'vue'
import { BellOff } from '@lucide/vue'
import { listNotifications, markAllNotificationsRead, markNotificationRead } from '@/api/notification'
import { formatMediaRelativeTime } from '@/components/media/format'
import { cn } from '@/utils/cn'
import type { NotificationItem } from '@/types/notification'

const items = ref<NotificationItem[]>([])
const loading = ref(true)

const unreadCount = computed(() => items.value.filter((item) => !item.isRead).length)

async function load() {
  loading.value = true
  try {
    items.value = await listNotifications()
  } catch {
    // 静默：请求异常已由全局拦截统一展示
  } finally {
    loading.value = false
  }
}

async function handleRead(item: NotificationItem) {
  if (item.isRead) return
  item.isRead = true
  try {
    await markNotificationRead(item.id)
  } catch {
    item.isRead = false
  }
}

async function handleReadAll() {
  if (unreadCount.value === 0) return
  const snapshot = items.value.map((item) => item.isRead)
  items.value.forEach((item) => {
    item.isRead = true
  })
  try {
    await markAllNotificationsRead()
  } catch {
    items.value.forEach((item, index) => {
      item.isRead = snapshot[index]
    })
  }
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-3xl p-6">
    <div class="mb-5 flex items-center justify-between">
      <h1 class="text-xl font-bold text-surface-900">
        通知
      </h1>
      <button
        type="button"
        :class="
          cn(
            'rounded-xl px-3 py-1.5 text-sm transition-colors',
            unreadCount > 0 ? 'text-primary-600 hover:bg-primary-50' : 'cursor-not-allowed text-surface-400',
          )
        "
        :disabled="unreadCount === 0"
        @click="handleReadAll"
      >
        全部已读
      </button>
    </div>

    <p
      v-if="loading"
      class="py-16 text-center text-sm text-surface-400"
    >
      加载中…
    </p>
    <div
      v-else-if="items.length === 0"
      class="flex flex-col items-center gap-3 rounded-2xl border border-dashed border-surface-200 bg-white py-16 text-surface-400"
    >
      <BellOff class="h-8 w-8" />
      <p class="text-sm">
        暂无通知
      </p>
    </div>
    <ul
      v-else
      class="overflow-hidden rounded-2xl border border-surface-200 bg-white shadow-soft"
    >
      <li
        v-for="item in items"
        :key="item.id"
        class="border-b border-surface-100 last:border-b-0"
      >
        <button
          type="button"
          class="flex w-full gap-3 px-5 py-4 text-left transition-colors hover:bg-surface-50"
          @click="handleRead(item)"
        >
          <span
            :class="cn('mt-2 h-2 w-2 shrink-0 rounded-full', item.isRead ? 'bg-transparent' : 'bg-primary-500')"
          />
          <span class="min-w-0 flex-1">
            <span class="flex items-baseline justify-between gap-3">
              <span
                :class="cn('truncate text-sm', item.isRead ? 'font-medium text-surface-600' : 'font-semibold text-surface-900')"
              >{{ item.title }}</span>
              <span class="shrink-0 text-xs text-surface-400">{{ formatMediaRelativeTime(item.createTime) }}</span>
            </span>
            <span
              v-if="item.content"
              class="mt-1 block text-sm text-surface-500"
            >{{ item.content }}</span>
          </span>
        </button>
      </li>
    </ul>
  </div>
</template>
