<script setup lang="ts">
/**
 * 通知铃铛：未读角标 + 下拉面板（PC 与移动端共用）。
 * 未读数 30 秒轮询刷新（仅登录态），面板内单条点击标已读，支持全部已读与跳转通知页。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Bell } from '@lucide/vue'
import { getUnreadCount, listNotifications, markAllNotificationsRead, markNotificationRead } from '@/api/notification'
import { useUserStore } from '@/store/user'
import { formatMediaRelativeTime } from '@/components/media/format'
import { cn } from '@/utils/cn'
import type { NotificationItem } from '@/types/notification'

/** 未读数轮询间隔（ADR 0039：站内通知用 30 秒轮询而非长连接） */
const POLL_INTERVAL_MS = 30_000

/** 下拉面板展示的最近通知条数 */
const PREVIEW_LIMIT = 10

const router = useRouter()
const userStore = useUserStore()

const unreadCount = ref(0)
const items = ref<NotificationItem[]>([])
const open = ref(false)
const loading = ref(false)

let pollTimer: ReturnType<typeof setInterval> | null = null

const badgeText = computed(() => (unreadCount.value > 99 ? '99+' : String(unreadCount.value)))
const previewItems = computed(() => items.value.slice(0, PREVIEW_LIMIT))

/** 刷新未读数，失败静默（网络/登录态问题由全局请求层统一处理） */
async function refreshUnreadCount() {
  if (!userStore.isLoggedIn) return
  try {
    unreadCount.value = Number(await getUnreadCount()) || 0
  } catch {
    // 静默：轮询失败不打扰用户
  }
}

async function loadItems() {
  loading.value = true
  try {
    items.value = await listNotifications()
    unreadCount.value = items.value.filter((item) => !item.isRead).length
  } catch {
    // 静默：下拉面板保留上次数据
  } finally {
    loading.value = false
  }
}

function startPolling() {
  if (pollTimer !== null) return
  pollTimer = setInterval(refreshUnreadCount, POLL_INTERVAL_MS)
}

function stopPolling() {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function togglePanel() {
  open.value = !open.value
  if (open.value) {
    await loadItems()
  }
}

async function handleItemClick(item: NotificationItem) {
  if (item.isRead) return
  const previousCount = unreadCount.value
  item.isRead = true
  unreadCount.value = Math.max(0, previousCount - 1)
  try {
    await markNotificationRead(item.id)
  } catch {
    item.isRead = false
    unreadCount.value = previousCount
  }
}

async function handleReadAll() {
  if (unreadCount.value === 0) return
  const previousCount = unreadCount.value
  const previousReadState = items.value.map((item) => item.isRead)
  items.value.forEach((item) => {
    item.isRead = true
  })
  unreadCount.value = 0
  try {
    await markAllNotificationsRead()
  } catch {
    items.value.forEach((item, index) => {
      item.isRead = previousReadState[index]
    })
    unreadCount.value = previousCount
  }
}

function handleViewAll() {
  open.value = false
  router.push('/notifications')
}

watch(
  () => userStore.isLoggedIn,
  (loggedIn) => {
    if (loggedIn) {
      refreshUnreadCount()
      startPolling()
    } else {
      stopPolling()
      unreadCount.value = 0
      items.value = []
      open.value = false
    }
  },
)

onMounted(() => {
  if (userStore.isLoggedIn) {
    refreshUnreadCount()
    startPolling()
  }
})

onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="relative">
    <button
      type="button"
      :class="
        cn(
          'relative flex h-9 w-9 items-center justify-center rounded-xl text-surface-600 transition-colors hover:bg-surface-100 hover:text-surface-900',
          open && 'bg-surface-100 text-surface-900',
        )
      "
      aria-label="通知"
      :title="unreadCount > 0 ? `通知（${unreadCount} 条未读）` : '通知'"
      @click="togglePanel"
    >
      <Bell class="h-4 w-4" />
      <span
        v-if="unreadCount > 0"
        class="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-red-500 px-1 text-[10px] font-semibold leading-none text-white"
      >
        {{ badgeText }}
      </span>
    </button>

    <template v-if="open">
      <div
        class="fixed inset-0 z-40"
        @click="open = false"
      />
      <div
        class="absolute right-0 top-full z-50 mt-2 w-80 max-w-[calc(100vw-2rem)] overflow-hidden rounded-2xl border border-surface-200 bg-white shadow-card"
      >
        <div class="flex items-center justify-between border-b border-surface-100 px-4 py-3">
          <span class="text-sm font-semibold text-surface-900">通知</span>
          <button
            type="button"
            :class="cn('text-xs transition-colors', unreadCount > 0 ? 'text-primary-600 hover:text-primary-700' : 'cursor-not-allowed text-surface-400')"
            :disabled="unreadCount === 0"
            @click="handleReadAll"
          >
            全部已读
          </button>
        </div>

        <div class="max-h-96 overflow-y-auto">
          <p
            v-if="previewItems.length === 0 && !loading"
            class="px-4 py-10 text-center text-sm text-surface-400"
          >
            暂无通知
          </p>
          <button
            v-for="item in previewItems"
            :key="item.id"
            type="button"
            class="flex w-full gap-2 border-b border-surface-50 px-4 py-3 text-left transition-colors last:border-b-0 hover:bg-surface-50"
            @click="handleItemClick(item)"
          >
            <span
              :class="cn('mt-1.5 h-2 w-2 shrink-0 rounded-full', item.isRead ? 'bg-transparent' : 'bg-primary-500')"
            />
            <span class="min-w-0 flex-1">
              <span class="flex items-baseline justify-between gap-2">
                <span class="truncate text-sm font-medium text-surface-900">{{ item.title }}</span>
                <span class="shrink-0 text-xs text-surface-400">{{ formatMediaRelativeTime(item.createTime) }}</span>
              </span>
              <span
                v-if="item.content"
                class="mt-0.5 line-clamp-2 block text-xs text-surface-500"
              >{{ item.content }}</span>
            </span>
          </button>
        </div>

        <button
          type="button"
          class="flex w-full items-center justify-center border-t border-surface-100 py-2.5 text-sm text-primary-600 transition-colors hover:bg-surface-50"
          @click="handleViewAll"
        >
          查看全部
        </button>
      </div>
    </template>
  </div>
</template>
