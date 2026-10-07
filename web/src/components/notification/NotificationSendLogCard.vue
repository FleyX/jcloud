<script setup lang="ts">
/**
 * 邮件发送记录卡片（管理员）：分页展示最近发送记录
 */
import { onMounted, reactive, ref } from 'vue'
import { ChevronLeft, ChevronRight, RefreshCw } from '@lucide/vue'
import { fetchNotificationSendLogs } from '@/api/notification'
import { cn } from '@/utils/cn'
import type { PageResult } from '@/types/auth'
import type { NotificationSendLog } from '@/types/notification'

const query = reactive({
  pageNum: 1,
  pageSize: 10,
})

const pageData = ref<PageResult<NotificationSendLog>>({
  records: [],
  total: '0',
  size: '10',
  current: '1',
  pages: '0',
})
const loading = ref(false)

onMounted(loadLogs)

async function loadLogs() {
  loading.value = true
  try {
    pageData.value = await fetchNotificationSendLogs(query)
  } catch {
    // request.ts 已统一处理异常提示
  } finally {
    loading.value = false
  }
}

function handlePageChange(page: number) {
  if (page < 1 || page > Number(pageData.value.pages)) {
    return
  }
  query.pageNum = page
  loadLogs()
}
</script>

<template>
  <div class="rounded-2xl border border-surface-100 bg-white shadow-soft">
    <div class="flex items-center justify-between px-6 py-5">
      <div>
        <h3 class="text-base font-semibold text-surface-900">
          发送记录
        </h3>
        <p class="mt-1 text-xs text-surface-400">
          最近邮件发送结果，失败原因便于排查配置问题
        </p>
      </div>
      <button
        class="flex items-center gap-1 rounded-lg border border-surface-200 px-3 py-1.5 text-xs font-medium text-surface-600 transition-colors hover:bg-surface-50 disabled:opacity-50"
        :disabled="loading"
        @click="loadLogs"
      >
        <RefreshCw class="h-3.5 w-3.5" />
        刷新
      </button>
    </div>

    <div class="overflow-x-auto">
      <table class="w-full min-w-160 text-left text-sm">
        <thead class="bg-surface-50 text-xs text-surface-500">
          <tr>
            <th class="px-5 py-3 font-medium whitespace-nowrap">
              时间
            </th>
            <th class="px-5 py-3 font-medium whitespace-nowrap">
              收件人
            </th>
            <th class="px-5 py-3 font-medium whitespace-nowrap">
              事件类型
            </th>
            <th class="px-5 py-3 font-medium whitespace-nowrap">
              结果
            </th>
            <th class="px-5 py-3 font-medium">
              失败原因
            </th>
          </tr>
        </thead>
        <tbody class="divide-y divide-surface-100">
          <tr
            v-for="log in pageData.records"
            :key="log.id"
            class="hover:bg-surface-50"
          >
            <td class="px-5 py-3 text-surface-600 whitespace-nowrap">
              {{ log.createTime }}
            </td>
            <td class="px-5 py-3 text-surface-700">
              {{ log.recipient }}
            </td>
            <td class="px-5 py-3 text-surface-600">
              {{ log.eventTypeName }}
            </td>
            <td class="px-5 py-3">
              <span
                :class="cn(
                  'rounded-full px-2 py-0.5 text-xs font-medium',
                  log.success ? 'bg-emerald-50 text-emerald-600' : 'bg-red-50 text-red-600',
                )"
              >
                {{ log.success ? '成功' : '失败' }}
              </span>
            </td>
            <td class="px-5 py-3 text-surface-500">
              {{ log.errorMessage || '-' }}
            </td>
          </tr>
        </tbody>
      </table>
      <div
        v-if="!loading && pageData.records.length === 0"
        class="px-6 py-10 text-center text-sm text-surface-400"
      >
        暂无发送记录
      </div>
    </div>

    <div class="flex items-center justify-between border-t border-surface-200 px-5 py-3">
      <span class="text-xs text-surface-500">
        共 {{ pageData.total }} 条，第 {{ pageData.current }} / {{ pageData.pages }} 页
      </span>
      <div class="flex items-center gap-2">
        <button
          :disabled="query.pageNum <= 1"
          :class="cn('rounded-lg border border-surface-200 p-1.5 text-surface-600 hover:bg-surface-50', query.pageNum <= 1 && 'cursor-not-allowed opacity-50')"
          @click="handlePageChange(query.pageNum - 1)"
        >
          <ChevronLeft class="h-4 w-4" />
        </button>
        <button
          :disabled="query.pageNum >= Number(pageData.pages)"
          :class="cn('rounded-lg border border-surface-200 p-1.5 text-surface-600 hover:bg-surface-50', query.pageNum >= Number(pageData.pages) && 'cursor-not-allowed opacity-50')"
          @click="handlePageChange(query.pageNum + 1)"
        >
          <ChevronRight class="h-4 w-4" />
        </button>
      </div>
    </div>
  </div>
</template>
