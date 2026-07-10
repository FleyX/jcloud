<script setup lang="ts">
/**
 * 移动端我的分享页
 */
import { computed, onMounted, ref } from 'vue'
import {
  Copy,
  Link,
  Lock,
  MoreVertical,
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
const statusFilter = ref<number | undefined>(undefined)

const displayShares = computed(() =>
  shares.value.map((share) => ({
    ...share,
    shareUrl: `${window.location.origin}/s/${share.shareCode}`,
    displayExpire: formatExpire(share.expireAt),
  })),
)

function formatExpire(expireAt?: string): string {
  if (!expireAt) return '永久有效'
  const date = new Date(expireAt)
  const now = new Date()
  if (date <= now) return '已过期'
  return `${date.getMonth() + 1}/${date.getDate()} 过期`
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
    message: `确定删除分享 "${share.name}" 吗？`,
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
</script>

<template>
  <div class="flex h-full flex-col bg-surface-50">
    <!-- 顶部栏 -->
    <div class="sticky top-0 z-10 border-b border-surface-200 bg-white/90 px-4 py-3 backdrop-blur-md">
      <div class="mb-3 flex items-center justify-between">
        <h1 class="text-lg font-semibold text-surface-900">
          我的分享
        </h1>
        <div class="flex rounded-lg border border-surface-200 bg-surface-50 p-0.5">
          <button
            v-for="opt in statusOptions"
            :key="opt.label"
            :class="cn(
              'rounded-md px-2.5 py-1 text-xs font-medium transition-colors',
              statusFilter === opt.value
                ? 'bg-primary-100 text-primary-700'
                : 'text-surface-600 hover:bg-surface-100'
            )"
            @click="handleStatusChange(opt.value)"
          >
            {{ opt.label }}
          </button>
        </div>
      </div>
      <div class="flex items-center gap-2 rounded-xl border border-surface-200 bg-surface-50 px-3 py-2.5">
        <Search class="h-4 w-4 text-surface-400" />
        <input
          v-model="keyword"
          type="text"
          placeholder="搜索分享名称"
          class="flex-1 bg-transparent text-sm outline-none placeholder:text-surface-400"
          @keyup.enter="handleSearch"
        >
      </div>
    </div>

    <!-- 列表 -->
    <div class="flex-1 overflow-y-auto p-4">
      <div
        v-if="loading"
        class="py-10 text-center text-sm text-surface-500"
      >
        加载中...
      </div>
      <div
        v-else
        class="space-y-3"
      >
        <div
          v-for="share in displayShares"
          :key="share.id"
          class="relative rounded-2xl border border-surface-200 bg-white p-4 shadow-card"
        >
          <div class="flex items-start gap-3">
            <div class="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-primary-100 text-primary-600">
              <Link class="h-5 w-5" />
            </div>
            <div class="min-w-0 flex-1">
              <p class="truncate text-sm font-medium text-surface-900">
                {{ share.name }}
              </p>
              <p class="mt-0.5 flex items-center gap-2 text-xs text-surface-500">
                <span class="font-mono">{{ share.shareCode }}</span>
                <Lock
                  v-if="share.hasPassword"
                  class="h-3 w-3"
                />
              </p>
              <p class="mt-1 text-xs text-surface-500">
                {{ share.displayExpire }} · 访问 {{ share.viewCount }}{{ share.maxViews ? ` / ${share.maxViews}` : '' }}
              </p>
            </div>
            <button
              class="rounded-lg p-2 text-surface-400 hover:bg-surface-100"
              @click="toggleDropdown(share.id)"
            >
              <MoreVertical class="h-5 w-5" />
            </button>
          </div>

          <!-- 操作菜单 -->
          <div
            v-if="activeDropdownId === share.id"
            class="absolute right-4 top-14 z-10 w-32 rounded-xl border border-surface-200 bg-white py-1 shadow-card"
          >
            <button
              class="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-surface-700 hover:bg-surface-50"
              @click="copyLink(share.shareUrl); toggleDropdown(share.id)"
            >
              <Copy class="h-4 w-4" />
              复制链接
            </button>
            <button
              class="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-surface-700 hover:bg-surface-50"
              @click="openEditModal(share); toggleDropdown(share.id)"
            >
              <Pencil class="h-4 w-4" />
              编辑
            </button>
            <button
              class="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-surface-700 hover:bg-surface-50"
              @click="toggleStatus(share); toggleDropdown(share.id)"
            >
              <component
                :is="share.status === 1 ? PowerOff : Power"
                class="h-4 w-4"
              />
              {{ share.status === 1 ? '停用' : '启用' }}
            </button>
            <button
              class="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-red-600 hover:bg-red-50"
              @click="handleDelete(share); toggleDropdown(share.id)"
            >
              <Trash2 class="h-4 w-4" />
              删除
            </button>
          </div>
        </div>
      </div>

      <p
        v-if="!loading && displayShares.length === 0"
        class="py-10 text-center text-sm text-surface-500"
      >
        暂无分享
      </p>

      <div
        v-if="total > pageSize"
        class="mt-4 flex items-center justify-center gap-4"
      >
        <button
          :disabled="pageNum <= 1"
          class="text-sm text-surface-600 disabled:text-surface-300"
          @click="pageNum--; loadShares()"
        >
          上一页
        </button>
        <span class="text-sm text-surface-500">{{ pageNum }} / {{ Math.ceil(total / pageSize) }}</span>
        <button
          :disabled="pageNum >= Math.ceil(total / pageSize)"
          class="text-sm text-surface-600 disabled:text-surface-300"
          @click="pageNum++; loadShares()"
        >
          下一页
        </button>
      </div>
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
