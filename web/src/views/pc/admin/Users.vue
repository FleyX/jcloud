<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  batchDeleteUser,
  batchUpdateUserStatus,
  createUser,
  deleteUser,
  fetchUserPage,
  updateUser,
  updateUserStatus,
} from '@/api/user'
import { fetchAllRoles } from '@/api/role'
import { fetchStorageSpacePage } from '@/api/storage-space'
import { cn } from '@/utils/cn'
import { useConfirmStore } from '@/store/confirm'
import { useNotificationStore } from '@/store/notification'
import UserCreateDialog from './components/UserCreateDialog.vue'
import UserEditDialog from './components/UserEditDialog.vue'
import UserMigrationDialog from './components/UserMigrationDialog.vue'
import type { BatchUserStatusDto, PageResult, RoleVo, UserSaveDto, UserUpdateDto, UserVo } from '@/types/auth'
import type { StorageSpaceVo } from '@/types/storage-space'
import {
  Users,
  Pencil,
  Trash2,
  ChevronLeft,
  ChevronRight,
  Plus,
  Truck,
} from '@lucide/vue'
import { SwitchRoot, SwitchThumb } from 'radix-vue'

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
const selectedUserIds = ref<string[]>([])
const confirmStore = useConfirmStore()
const notificationStore = useNotificationStore()

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
const migrationDialogOpen = ref(false)
const migrationUser = ref<(UserVo & { storageSpaceId?: string }) | null>(null)
const spaces = ref<StorageSpaceVo[]>([])

const primarySpace = computed(() => spaces.value.find((s) => s.isPrimary === 1) || spaces.value[0] || null)

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
    const data = await fetchUserPage(query)
    pageData.value = data
    selectedUserIds.value = selectedUserIds.value.filter((id) => data.records.some((u) => u.id === id))
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

function formatBytes(bytes?: string | number): string {
  const num = Number(bytes)
  if (!num) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let size = num
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return `${size.toFixed(2)} ${units[i]}`
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
  } catch {
    // request.ts 已统一处理异常提示
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
  } catch {
    // request.ts 已统一处理异常提示
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
  } catch {
    // request.ts 已统一处理异常提示
  } finally {
    submitting.value = false
  }
}

function openMigrationDialog(user: UserVo) {
  migrationUser.value = user as UserVo & { storageSpaceId?: string }
  migrationDialogOpen.value = true
}

async function handleMigrationSuccess() {
  notificationStore.success('迁移完成')
  await loadUsers()
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
  } catch {
    // request.ts 已统一处理异常提示
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
  } catch {
    // request.ts 已统一处理异常提示
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
  } catch {
    // request.ts 已统一处理异常提示
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
  <div class="flex h-full flex-col gap-5">
    <!-- Header -->
    <div class="flex flex-col gap-4 rounded-2xl border border-surface-200 bg-white p-5 shadow-card sm:flex-row sm:items-center sm:justify-between">
      <div class="flex items-center gap-3">
        <div class="flex h-10 w-10 items-center justify-center rounded-xl bg-primary-100 text-primary-600">
          <Users class="h-5 w-5" />
        </div>
        <div>
          <h2 class="text-lg font-bold text-surface-900">
            用户管理
          </h2>
          <p class="text-xs text-surface-500">
            管理系统用户及其角色权限
          </p>
        </div>
      </div>

      <div class="flex flex-wrap items-center gap-2">
        <input
          v-model="query.username"
          type="text"
          placeholder="用户名"
          class="rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
        >
        <input
          v-model="query.nickname"
          type="text"
          placeholder="昵称"
          class="rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
        >
        <select
          v-model="query.status"
          class="rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
        >
          <option :value="undefined">
            全部状态
          </option>
          <option :value="1">
            启用
          </option>
          <option :value="0">
            禁用
          </option>
        </select>
        <button
          class="rounded-xl bg-primary-600 px-4 py-2 text-sm font-medium text-white shadow-soft transition-colors hover:bg-primary-700"
          @click="handleSearch"
        >
          查询
        </button>
        <button
          class="flex items-center gap-1 rounded-xl bg-emerald-600 px-4 py-2 text-sm font-medium text-white shadow-soft transition-colors hover:bg-emerald-700"
          @click="openCreateDialog"
        >
          <Plus class="h-4 w-4" />
          新增用户
        </button>
      </div>
    </div>

    <!-- Batch toolbar -->
    <div
      v-if="selectedUserIds.length > 0"
      class="flex items-center justify-between rounded-xl border border-primary-100 bg-primary-50 px-4 py-2 text-sm"
    >
      <span class="text-surface-700">
        已选择 <span class="font-semibold text-primary-700">{{ selectedUserIds.length }}</span> 个用户
      </span>
      <div class="flex items-center gap-2">
        <button
          :disabled="submitting"
          class="rounded-lg bg-white px-3 py-1.5 text-xs font-medium text-surface-700 shadow-sm transition-colors hover:bg-surface-50 disabled:cursor-not-allowed disabled:opacity-60"
          @click="handleBatchStatus(0)"
        >
          批量禁用
        </button>
        <button
          :disabled="submitting"
          class="rounded-lg bg-white px-3 py-1.5 text-xs font-medium text-surface-700 shadow-sm transition-colors hover:bg-surface-50 disabled:cursor-not-allowed disabled:opacity-60"
          @click="handleBatchStatus(1)"
        >
          批量启用
        </button>
        <button
          :disabled="submitting"
          class="rounded-lg bg-red-50 px-3 py-1.5 text-xs font-medium text-red-600 transition-colors hover:bg-red-100 disabled:cursor-not-allowed disabled:opacity-60"
          @click="handleBatchDelete"
        >
          批量删除
        </button>
      </div>
    </div>

    <!-- Table -->
    <div class="flex-1 overflow-hidden rounded-2xl border border-surface-200 bg-white shadow-card">
      <div class="overflow-x-auto">
        <table class="w-full text-left text-sm">
          <thead class="bg-surface-50 text-xs uppercase text-surface-500">
            <tr>
              <th class="px-5 py-3 font-medium">
                <input
                  type="checkbox"
                  :checked="allSelected"
                  :indeterminate.prop="someSelected"
                  class="h-4 w-4 rounded border-surface-300 text-primary-600 focus:ring-primary-500"
                  @change="toggleSelectAll"
                >
              </th>
              <th class="px-5 py-3 font-medium">
                用户名
              </th>
              <th class="px-5 py-3 font-medium">
                昵称
              </th>
              <th class="px-5 py-3 font-medium">
                邮箱
              </th>
              <th class="px-5 py-3 font-medium">
                角色
              </th>
              <th class="px-5 py-3 font-medium">
                存储空间
              </th>
              <th class="px-5 py-3 font-medium">
                限额
              </th>
              <th class="px-5 py-3 font-medium">
                状态
              </th>
              <th class="px-5 py-3 font-medium">
                操作
              </th>
            </tr>
          </thead>
          <tbody class="divide-y divide-surface-100">
            <tr
              v-for="user in pageData.records"
              :key="user.id"
              class="hover:bg-surface-50/50"
            >
              <td class="px-5 py-3">
                <input
                  type="checkbox"
                  :checked="selectedUserIds.includes(user.id)"
                  :disabled="!isSelectable(user)"
                  class="h-4 w-4 rounded border-surface-300 text-primary-600 focus:ring-primary-500 disabled:cursor-not-allowed disabled:opacity-50"
                  @change="toggleSelect(user)"
                >
              </td>
              <td class="px-5 py-3 font-medium text-surface-900">
                {{ user.username }}
                <span
                  v-if="user.isAdmin"
                  class="ml-2 rounded-full bg-primary-100 px-2 py-0.5 text-[10px] font-semibold text-primary-700"
                >
                  admin
                </span>
              </td>
              <td class="px-5 py-3 text-surface-600">
                {{ user.nickname || '-' }}
              </td>
              <td class="px-5 py-3 text-surface-600">
                {{ user.email || '-' }}
              </td>
              <td class="px-5 py-3">
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
              </td>
              <td class="px-5 py-3 text-surface-600">
                {{ user.storageSpaceName || '-' }}
              </td>
              <td class="px-5 py-3 text-surface-600">
                {{ formatBytes(user.quota) }}
              </td>
              <td class="px-5 py-3">
                <SwitchRoot
                  :checked="user.status === 1"
                  :disabled="user.isAdmin || submitting"
                  class="relative h-6 w-11 cursor-pointer rounded-full bg-surface-200 outline-none transition-colors data-[state=checked]:bg-primary-600 data-[disabled]:cursor-not-allowed data-[disabled]:opacity-50"
                  @update:checked="handleToggleStatus(user)"
                >
                  <SwitchThumb
                    class="block h-5 w-5 translate-x-0.5 rounded-full bg-white shadow-sm transition-transform data-[state=checked]:translate-x-[22px]"
                  />
                </SwitchRoot>
              </td>
              <td class="px-5 py-3">
                <div class="flex items-center gap-2">
                  <button
                    class="flex items-center gap-1 rounded-lg bg-surface-100 px-2.5 py-1.5 text-xs font-medium text-surface-700 transition-colors hover:bg-surface-200"
                    @click="openEditDialog(user)"
                  >
                    <Pencil class="h-3.5 w-3.5" />
                    编辑
                  </button>
                  <button
                    class="flex items-center gap-1 rounded-lg bg-primary-50 px-2.5 py-1.5 text-xs font-medium text-primary-600 transition-colors hover:bg-primary-100"
                    @click="openMigrationDialog(user)"
                  >
                    <Truck class="h-3.5 w-3.5" />
                    迁移
                  </button>
                  <button
                    v-if="!user.isAdmin"
                    class="flex items-center gap-1 rounded-lg bg-red-50 px-2.5 py-1.5 text-xs font-medium text-red-600 transition-colors hover:bg-red-100"
                    @click="handleDeleteUser(user)"
                  >
                    <Trash2 class="h-3.5 w-3.5" />
                    删除
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Pagination -->
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

    <UserMigrationDialog
      v-model:open="migrationDialogOpen"
      :user="migrationUser"
      :spaces="spaces"
      @success="handleMigrationSuccess"
    />
  </div>
</template>
