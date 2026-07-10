<script setup lang="ts">
/**
 * PC 我的分享页
 * - 展示当前用户创建的分享
 * - 支持搜索、状态筛选、编辑、启用/停用、删除、复制链接
 */
import { computed, onMounted, ref } from 'vue'
import {
  Copy,
  Link,
  Lock,
  MoreHorizontal,
  Pencil,
  Power,
  PowerOff,
  Search,
  Trash2,
} from '@lucide/vue'
import { cn } from '@/utils/cn'
import { deleteShare, fetchShareDetail, fetchSharePage, updateShare } from '@/api/share'
import { useConfirmStore } from '@/store/confirm'
import { useNotificationStore } from '@/store/notification'
import CreateShareModal from '@/views/files/components/CreateShareModal.vue'
import type { ShareCreateRequest, ShareDetailVo, ShareUpdateRequest, ShareVo } from '@/types/share'

const confirmStore = useConfirmStore()
const notificationStore = useNotificationStore()

const shares = ref<ShareVo[]>([])
const loading = ref(false)
const keyword = ref('')
const statusFilter = ref<number | undefined>(undefined)
const pageNum = ref(1)
const pageSize = ref(20)
const total = ref(0)

const shareModalOpen = ref(false)
const shareEditTarget = ref<ShareDetailVo | undefined>(undefined)
const activeDropdownId = ref<string | null>(null)

const statusOptions = [
  { value: undefined, label: '全部' },
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
]

const displayShares = computed(() =>
  shares.value.map((share) => ({
    ...share,
    displayExpire: formatExpire(share.expireAt),
    displayViews: formatViews(share.viewCount, share.maxViews),
    shareUrl: buildShareUrl(share.shareCode),
  })),
)

function buildShareUrl(code: string): string {
  return `${window.location.origin}/s/${code}`
}

function formatExpire(expireAt?: string): string {
  if (!expireAt) return '永久有效'
  const date = new Date(expireAt)
  const now = new Date()
  if (date <= now) return '已过期'
  return date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

function formatViews(viewCount: string, maxViews?: string): string {
  const current = Number(viewCount)
  if (!maxViews) return `${current}`
  return `${current} / ${maxViews}`
}

async function loadShares() {
  loading.value = true
  try {
    const res = await fetchSharePage({
      name: keyword.value.trim() || undefined,
      status: statusFilter.value,
      pageNum: pageNum.value,
      pageSize: pageSize.value,
    })
    shares.value = res.records
    total.value = Number(res.total)
  } finally {
    loading.value = false
  }
}

onMounted(loadShares)

function handleSearch() {
  pageNum.value = 1
  loadShares()
}

function handleStatusChange(value: number | undefined) {
  statusFilter.value = value
  handleSearch()
}

async function copyLink(url: string) {
  try {
    await navigator.clipboard.writeText(url)
    notificationStore.success('链接已复制')
  } catch {
    notificationStore.error('复制失败')
  }
}

async function openEditModal(share: ShareVo) {
  try {
    const detail = await fetchShareDetail(share.id)
    shareEditTarget.value = detail
    shareModalOpen.value = true
  } catch (err) {
    const message = err instanceof Error ? err.message : '加载分享详情失败'
    notificationStore.error(message)
  }
}

function closeEditModal() {
  shareModalOpen.value = false
  shareEditTarget.value = undefined
}

async function handleUpdateShare(payload: ShareUpdateRequest) {
  if (!shareEditTarget.value) return
  try {
    await updateShare(shareEditTarget.value.id, payload)
    notificationStore.success('分享已更新')
    closeEditModal()
    await loadShares()
  } catch {
    // 请求层已统一提示
  }
}

async function handleShareConfirm(payload: ShareCreateRequest | ShareUpdateRequest) {
  if (shareEditTarget.value) {
    await handleUpdateShare(payload as ShareUpdateRequest)
  }
}

async function toggleStatus(share: ShareVo) {
  const nextStatus = share.status === 1 ? 0 : 1
  const action = nextStatus === 1 ? '启用' : '停用'
  const confirmed = await confirmStore.open({
    title: `${action}分享`,
    message: `确定${action}分享 "${share.name}" 吗？`,
    confirmText: action,
  })
  if (!confirmed) return
  try {
    await updateShare(share.id, { status: nextStatus } as Parameters<typeof updateShare>[1])
    notificationStore.success(`分享已${action}`)
    await loadShares()
  } catch (err) {
    const message = err instanceof Error ? err.message : `${action}分享失败`
    notificationStore.error(message)
  }
}

async function handleDelete(share: ShareVo) {
  const confirmed = await confirmStore.open({
    title: '删除分享',
    message: `确定删除分享 "${share.name}" 吗？删除后链接将失效。`,
    confirmText: '删除',
    type: 'danger',
  })
  if (!confirmed) return
  try {
    await deleteShare(share.id)
    notificationStore.success('分享已删除')
    await loadShares()
  } catch (err) {
    const message = err instanceof Error ? err.message : '删除分享失败'
    notificationStore.error(message)
  }
}

function toggleDropdown(id: string) {
  activeDropdownId.value = activeDropdownId.value === id ? null : id
}

function closeDropdown() {
  activeDropdownId.value = null
}
</script>

<template>
  <div class="mx-auto h-full max-w-7xl">
    <!-- 顶部工具栏 -->
    <div class="mb-6 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
      <h1 class="text-lg font-semibold text-surface-900">
        我的分享
      </h1>

      <div class="flex items-center gap-3">
        <div class="flex rounded-xl border border-surface-200 bg-white p-1 shadow-soft">
          <button
            v-for="opt in statusOptions"
            :key="opt.label"
            :class="cn(
              'rounded-lg px-3 py-1.5 text-xs font-medium transition-colors',
              statusFilter === opt.value
                ? 'bg-primary-100 text-primary-700'
                : 'text-surface-600 hover:bg-surface-50'
            )"
            @click="handleStatusChange(opt.value)"
          >
            {{ opt.label }}
          </button>
        </div>

        <div class="relative">
          <Search class="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-surface-400" />
          <input
            v-model="keyword"
            type="text"
            placeholder="搜索分享名称"
            class="h-9 w-44 rounded-xl border border-surface-200 bg-white pl-9 pr-3 text-sm outline-none transition-all focus:border-primary-300 focus:ring-2 focus:ring-primary-100"
            @keyup.enter="handleSearch"
          >
        </div>
      </div>
    </div>

    <!-- 分享列表 -->
    <div class="overflow-hidden rounded-3xl border border-surface-200 bg-white shadow-soft">
      <!-- 表头 -->
      <div
        class="grid grid-cols-[1fr_120px_100px_120px_100px_100px] items-center border-b border-surface-200 bg-surface-50/80 px-5 py-3 text-xs font-semibold uppercase tracking-wider text-surface-500"
      >
        <span>分享名称</span>
        <span>短码</span>
        <span>访问次数</span>
        <span>有效期</span>
        <span>状态</span>
        <span class="text-right">操作</span>
      </div>

      <!-- 加载中 -->
      <div
        v-if="loading"
        class="px-5 py-12 text-center text-sm text-surface-500"
      >
        加载中...
      </div>

      <!-- 分享行 -->
      <div
        v-else
        class="divide-y divide-surface-100"
      >
        <div
          v-for="share in displayShares"
          :key="share.id"
          class="group grid grid-cols-[1fr_120px_100px_120px_100px_100px] items-center px-5 py-3.5 text-sm transition-colors hover:bg-surface-50"
        >
          <div class="flex min-w-0 items-center gap-3 pr-4">
            <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-primary-100 text-primary-600">
              <Link class="h-5 w-5" />
            </div>
            <div class="min-w-0">
              <p class="line-clamp-1 font-medium text-surface-800">
                {{ share.name }}
              </p>
              <p class="flex items-center gap-1 text-xs text-surface-500">
                <span>{{ share.createTime.replace(' ', ' ').split(' ')[0] }}</span>
                <Lock
                  v-if="share.hasPassword"
                  class="h-3 w-3"
                />
              </p>
            </div>
          </div>

          <span class="font-mono text-surface-600">{{ share.shareCode }}</span>

          <span class="text-surface-600">{{ share.displayViews }}</span>

          <span
            :class="cn(
              'text-surface-600',
              share.displayExpire === '已过期' && 'text-red-500'
            )"
          >
            {{ share.displayExpire }}
          </span>

          <span>
            <span
              :class="cn(
                'inline-flex rounded-full px-2 py-0.5 text-xs font-medium',
                share.status === 1
                  ? 'bg-emerald-100 text-emerald-700'
                  : 'bg-surface-100 text-surface-600'
              )"
            >
              {{ share.status === 1 ? '启用' : '停用' }}
            </span>
          </span>

          <div class="relative flex justify-end">
            <button
              class="rounded-lg p-1.5 text-surface-500 transition-colors hover:bg-surface-100"
              @click="toggleDropdown(share.id)"
            >
              <MoreHorizontal class="h-4 w-4" />
            </button>

            <div
              v-if="activeDropdownId === share.id"
              class="absolute right-0 top-full z-10 mt-1 w-36 rounded-xl border border-surface-200 bg-white py-1 shadow-card"
            >
              <button
                class="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-surface-700 transition-colors hover:bg-surface-50"
                @click="copyLink(share.shareUrl); closeDropdown()"
              >
                <Copy class="h-4 w-4" />
                复制链接
              </button>
              <button
                class="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-surface-700 transition-colors hover:bg-surface-50"
                @click="openEditModal(share); closeDropdown()"
              >
                <Pencil class="h-4 w-4" />
                编辑
              </button>
              <button
                class="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-surface-700 transition-colors hover:bg-surface-50"
                @click="toggleStatus(share); closeDropdown()"
              >
                <component
                  :is="share.status === 1 ? PowerOff : Power"
                  class="h-4 w-4"
                />
                {{ share.status === 1 ? '停用' : '启用' }}
              </button>
              <button
                class="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-red-600 transition-colors hover:bg-red-50"
                @click="handleDelete(share); closeDropdown()"
              >
                <Trash2 class="h-4 w-4" />
                删除
              </button>
            </div>
          </div>
        </div>

        <p
          v-if="displayShares.length === 0"
          class="px-5 py-12 text-center text-sm text-surface-500"
        >
          暂无分享
        </p>
      </div>
    </div>

    <!-- 分页 -->
    <div
      v-if="total > pageSize"
      class="mt-4 flex items-center justify-end gap-2"
    >
      <button
        :disabled="pageNum <= 1"
        class="rounded-lg px-3 py-1.5 text-sm font-medium text-surface-600 transition-colors hover:bg-surface-100 disabled:cursor-not-allowed disabled:text-surface-300"
        @click="pageNum--; loadShares()"
      >
        上一页
      </button>
      <span class="text-sm text-surface-500">{{ pageNum }} / {{ Math.ceil(total / pageSize) }}</span>
      <button
        :disabled="pageNum >= Math.ceil(total / pageSize)"
        class="rounded-lg px-3 py-1.5 text-sm font-medium text-surface-600 transition-colors hover:bg-surface-100 disabled:cursor-not-allowed disabled:text-surface-300"
        @click="pageNum++; loadShares()"
      >
        下一页
      </button>
    </div>

    <CreateShareModal
      :open="shareModalOpen"
      :item-ids="[]"
      :edit-share="shareEditTarget"
      @close="closeEditModal"
      @confirm="handleShareConfirm"
    />
  </div>
</template>
