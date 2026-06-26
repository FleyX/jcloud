<script setup lang="ts">
/**
 * 移动端用户管理
 * - 卡片列表展示用户，复用 PC 端的创建/编辑弹窗组件
 */
import { computed, onMounted, reactive, ref } from 'vue'
import {
  fetchUserPage,
  createUser,
  updateUser,
  deleteUser,
  updateUserStatus,
  batchDeleteUser,
  batchUpdateUserStatus,
} from '@/api/user'
import { fetchAllRoles } from '@/api/role'
import { fetchStorageSpacePage } from '@/api/storage-space'
import { useConfirmStore } from '@/store/confirm'
import { useNotificationStore } from '@/store/notification'
import { cn } from '@/utils/cn'
import { Plus, Search, Trash2, Pencil, ChevronLeft, ChevronRight } from '@lucide/vue'
import UserCreateDialog from '@/views/pc/admin/components/UserCreateDialog.vue'
import UserEditDialog from '@/views/pc/admin/components/UserEditDialog.vue'
import type { BatchUserStatusDto, PageResult, RoleVo, UserSaveDto, UserUpdateDto, UserVo } from '@/types/auth'
import type { StorageSpaceVo } from '@/types/storage-space'

const confirmStore = useConfirmStore()
const notificationStore = useNotificationStore()

const query = reactive({
  username: '',
  nickname: '',
  status: undefined as number | undefined,
  pageNum: 1,
  pageSize: 10,
})

const pageData = ref<PageResult<UserVo>>({
  records: [],
  total: '0',
  size: '10',
  current: '1',
  pages: '0',
})
const loading = ref(false)
const submitting = ref(false)
const roles = ref<RoleVo[]>([])
const spaces = ref<StorageSpaceVo[]>([])

const createDialogOpen = ref(false)
const createForm = reactive<UserSaveDto>({
  username: '',
  nickname: '',
  email: '',
  password: '',
  storageSpaceId: '',
  quota: '10',
  quotaUnit: 'GB',
})

const editDialogOpen = ref(false)
const editForm = reactive<UserUpdateDto & { storageSpaceId?: string; quota?: string; quotaUnit?: string }>({
  id: '',
  nickname: '',
  email: '',
  roleIds: [],
  status: 1,
  password: '',
  storageSpaceId: '',
  quota: '10',
  quotaUnit: 'GB',
})
const editingUser = ref<UserVo | null>(null)

const primarySpace = computed(() => spaces.value.find((s) => s.isPrimary === 1) || spaces.value[0] || null)

const selectedUserIds = ref<string[]>([])

const selectableUsers = computed(() => pageData.value.records.filter((u) => !u.isAdmin))
const allSelected = computed(
  () => selectableUsers.value.length > 0 && selectableUsers.value.every((u) => selectedUserIds.value.includes(u.id)),
)
const someSelected = computed(
  () => selectableUsers.value.some((u) => selectedUserIds.value.includes(u.id)) && !allSelected.value,
)

async function loadUsers() {
  loading.value = true
  try {
    pageData.value = await fetchUserPage(query)
    selectedUserIds.value = selectedUserIds.value.filter((id) =>
      pageData.value.records.some((u) => u.id === id),
    )
  } finally {
    loading.value = false
  }
}

async function loadRoles() {
  roles.value = await fetchAllRoles()
}

async function loadSpaces() {
  const data = await fetchStorageSpacePage({ pageNum: 1, pageSize: 500 })
  spaces.value = data.records.filter((s) => s.status === 1)
}

function handleSearch() {
  query.pageNum = 1
  loadUsers()
}

function handlePageChange(page: number) {
  query.pageNum = page
  loadUsers()
}

function isSelectable(user: UserVo): boolean {
  return !user.isAdmin
}

function toggleSelectAll() {
  if (allSelected.value) {
    selectableUsers.value.forEach((u) => {
      selectedUserIds.value = selectedUserIds.value.filter((id) => id !== u.id)
    })
  } else {
    selectableUsers.value.forEach((u) => {
      if (!selectedUserIds.value.includes(u.id)) {
        selectedUserIds.value.push(u.id)
      }
    })
  }
}

function toggleSelect(user: UserVo) {
  if (!isSelectable(user)) return
  const index = selectedUserIds.value.indexOf(user.id)
  if (index >= 0) {
    selectedUserIds.value.splice(index, 1)
  } else {
    selectedUserIds.value.push(user.id)
  }
}

async function handleToggleStatus(user: UserVo) {
  if (user.isAdmin || submitting.value) return
  const nextStatus = user.status === 1 ? 0 : 1
  submitting.value = true
  try {
    await updateUserStatus(user.id, nextStatus)
    notificationStore.success('状态更新成功')
    await loadUsers()
  } finally {
    submitting.value = false
  }
}

function openCreateDialog() {
  createForm.username = ''
  createForm.nickname = ''
  createForm.email = ''
  createForm.password = ''
  createForm.storageSpaceId = primarySpace.value?.id || ''
  createForm.quota = '10'
  createForm.quotaUnit = 'GB'
  createDialogOpen.value = true
}

async function submitCreateUser() {
  submitting.value = true
  try {
    await createUser(createForm)
    createDialogOpen.value = false
    notificationStore.success('用户创建成功')
    await loadUsers()
  } finally {
    submitting.value = false
  }
}

function openEditDialog(user: UserVo) {
  editingUser.value = user
  editForm.id = user.id
  editForm.nickname = user.nickname || ''
  editForm.email = user.email || ''
  editForm.roleIds = user.roles.map((role) => role.id)
  editForm.status = user.status
  editForm.password = ''
  editForm.storageSpaceId = user.storageSpaceId || ''
  editForm.quota = user.quota || '10'
  editForm.quotaUnit = user.quotaUnit || 'GB'
  editDialogOpen.value = true
}

async function submitEditUser() {
  if (!editingUser.value) return
  const dto: UserUpdateDto = {
    id: editForm.id,
    nickname: editForm.nickname,
    email: editForm.email,
    roleIds: editForm.roleIds,
    status: editForm.status,
    quota: editForm.quota,
    quotaUnit: editForm.quotaUnit,
  }
  if (editForm.password) {
    dto.password = editForm.password
  }
  submitting.value = true
  try {
    await updateUser(editingUser.value.id, dto)
    editDialogOpen.value = false
    notificationStore.success('用户信息更新成功')
    await loadUsers()
  } finally {
    submitting.value = false
  }
}

async function handleDeleteUser(user: UserVo) {
  if (user.isAdmin) return
  const confirmed = await confirmStore.open({
    title: '删除用户',
    message: `确定要删除用户 ${user.username} 吗？`,
    confirmText: '删除',
    type: 'danger',
  })
  if (!confirmed) return
  submitting.value = true
  try {
    await deleteUser(user.id)
    notificationStore.success('用户删除成功')
    await loadUsers()
  } finally {
    submitting.value = false
  }
}

async function handleBatchDelete() {
  if (selectedUserIds.value.length === 0) return
  const confirmed = await confirmStore.open({
    title: '批量删除用户',
    message: `确定要删除选中的 ${selectedUserIds.value.length} 个用户吗？`,
    confirmText: '删除',
    type: 'danger',
  })
  if (!confirmed) return
  submitting.value = true
  try {
    await batchDeleteUser(selectedUserIds.value)
    selectedUserIds.value = []
    notificationStore.success('批量删除成功')
    await loadUsers()
  } finally {
    submitting.value = false
  }
}

async function handleBatchStatus(status: number) {
  if (selectedUserIds.value.length === 0) return
  const dto: BatchUserStatusDto = {
    userIds: selectedUserIds.value,
    status,
  }
  submitting.value = true
  try {
    await batchUpdateUserStatus(dto)
    selectedUserIds.value = []
    notificationStore.success('批量状态更新成功')
    await loadUsers()
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadUsers()
  loadRoles()
  loadSpaces()
})
</script>

<template>
  <div class="flex h-full flex-col bg-surface-50">
    <!-- 顶部工具栏 -->
    <div class="sticky top-0 z-10 border-b border-surface-200 bg-white/90 px-4 py-3 backdrop-blur-md">
      <div class="flex items-center gap-2">
        <div class="flex flex-1 items-center gap-2 rounded-xl border border-surface-200 bg-surface-50 px-3 py-2">
          <Search class="h-4 w-4 text-surface-400" />
          <input
            v-model="query.username"
            type="text"
            placeholder="搜索用户名..."
            class="flex-1 bg-transparent text-sm outline-none placeholder:text-surface-400"
            @keyup.enter="handleSearch"
          >
        </div>
        <select
          v-model="query.status"
          class="rounded-xl border border-surface-200 bg-surface-50 px-2 py-2 text-sm outline-none"
          @change="handleSearch"
        >
          <option :value="undefined">
            全部
          </option>
          <option :value="1">
            启用
          </option>
          <option :value="0">
            禁用
          </option>
        </select>
        <button
          class="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-primary-600 text-white shadow-soft active:scale-95"
          @click="openCreateDialog"
        >
          <Plus class="h-5 w-5" />
        </button>
      </div>
    </div>

    <!-- 批量操作栏 -->
    <div
      v-if="selectedUserIds.length > 0"
      class="flex items-center justify-between border-b border-primary-100 bg-primary-50 px-4 py-2 text-sm"
    >
      <span class="text-surface-700">
        已选择 <span class="font-semibold text-primary-700">{{ selectedUserIds.length }}</span> 个用户
      </span>
      <div class="flex items-center gap-2">
        <button
          class="rounded-lg bg-white px-3 py-1 text-xs font-medium text-surface-700 shadow-sm"
          @click="handleBatchStatus(0)"
        >
          禁用
        </button>
        <button
          class="rounded-lg bg-white px-3 py-1 text-xs font-medium text-surface-700 shadow-sm"
          @click="handleBatchStatus(1)"
        >
          启用
        </button>
        <button
          class="rounded-lg bg-red-50 px-3 py-1 text-xs font-medium text-red-600"
          @click="handleBatchDelete"
        >
          删除
        </button>
      </div>
    </div>

    <!-- 用户列表 -->
    <div class="flex-1 overflow-y-auto p-4">
      <div class="mb-3 flex items-center gap-2 text-sm text-surface-600">
        <input
          type="checkbox"
          :checked="allSelected"
          :indeterminate.prop="someSelected"
          class="h-4 w-4 rounded border-surface-300 text-primary-600"
          @change="toggleSelectAll"
        >
        <span>全选本页</span>
      </div>

      <div class="space-y-3">
        <div
          v-for="user in pageData.records"
          :key="user.id"
          class="rounded-2xl border border-surface-200 bg-white p-4 shadow-card"
        >
          <div class="flex items-start justify-between">
            <div class="flex items-center gap-3">
              <input
                type="checkbox"
                :checked="selectedUserIds.includes(user.id)"
                :disabled="!isSelectable(user)"
                class="h-4 w-4 rounded border-surface-300 text-primary-600 disabled:opacity-50"
                @change="toggleSelect(user)"
              >
              <div>
                <p class="text-sm font-semibold text-surface-900">
                  {{ user.username }}
                  <span
                    v-if="user.isAdmin"
                    class="ml-1 rounded-full bg-primary-100 px-2 py-0.5 text-[10px] text-primary-700"
                  >admin</span>
                </p>
                <p class="text-xs text-surface-500">
                  {{ user.nickname || '-' }} · {{ user.email || '-' }}
                </p>
              </div>
            </div>
            <div class="flex items-center gap-1">
              <button
                class="rounded-lg p-2 text-surface-500 hover:bg-surface-100"
                @click="openEditDialog(user)"
              >
                <Pencil class="h-4 w-4" />
              </button>
              <button
                v-if="!user.isAdmin"
                class="rounded-lg p-2 text-red-500 hover:bg-red-50"
                @click="handleDeleteUser(user)"
              >
                <Trash2 class="h-4 w-4" />
              </button>
            </div>
          </div>

          <div class="mt-3 flex items-center justify-between">
            <div class="flex flex-wrap gap-1">
              <span
                v-for="role in user.roles"
                :key="role.id"
                class="rounded-md bg-surface-100 px-2 py-0.5 text-xs text-surface-600"
              >
                {{ role.name }}
              </span>
              <span
                v-if="user.roles.length === 0"
                class="text-xs text-surface-400"
              >未分配</span>
            </div>
            <button
              :disabled="user.isAdmin || submitting"
              :class="
                cn(
                  'rounded-full px-3 py-1 text-xs font-medium transition-colors',
                  user.status === 1
                    ? 'bg-emerald-100 text-emerald-700'
                    : 'bg-red-100 text-red-700',
                  (user.isAdmin || submitting) && 'opacity-50'
                )
              "
              @click="handleToggleStatus(user)"
            >
              {{ user.status === 1 ? '启用' : '禁用' }}
            </button>
          </div>
        </div>
      </div>

      <!-- 分页 -->
      <div class="mt-4 flex items-center justify-between">
        <span class="text-xs text-surface-500">
          共 {{ pageData.total }} 条
        </span>
        <div class="flex items-center gap-2">
          <button
            :disabled="query.pageNum <= 1"
            :class="cn('rounded-lg border border-surface-200 p-1.5 text-surface-600', query.pageNum <= 1 && 'opacity-50')"
            @click="handlePageChange(query.pageNum - 1)"
          >
            <ChevronLeft class="h-4 w-4" />
          </button>
          <span class="text-xs text-surface-600">{{ query.pageNum }} / {{ pageData.pages || 1 }}</span>
          <button
            :disabled="query.pageNum >= Number(pageData.pages)"
            :class="cn('rounded-lg border border-surface-200 p-1.5 text-surface-600', query.pageNum >= Number(pageData.pages) && 'opacity-50')"
            @click="handlePageChange(query.pageNum + 1)"
          >
            <ChevronRight class="h-4 w-4" />
          </button>
        </div>
      </div>
    </div>

    <UserCreateDialog
      v-model:open="createDialogOpen"
      :form="createForm"
      :spaces="spaces"
      :submitting="submitting"
      @submit="submitCreateUser"
    />
    <UserEditDialog
      v-model:open="editDialogOpen"
      :form="editForm"
      :editing-user="editingUser"
      :roles="roles"
      :spaces="spaces"
      :submitting="submitting"
      @submit="submitEditUser"
    />
  </div>
</template>
