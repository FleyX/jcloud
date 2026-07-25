<script setup lang="ts">
/**
 * 移动端角色管理
 * - 卡片列表展示角色，复用 PC 端的 RoleDialog 弹窗组件
 */
import { onMounted, reactive, ref } from 'vue'
import {
  createRole,
  deleteRole,
  fetchRolePage,
  updateRole,
  updateRoleStatus,
} from '@/api/role'
import { fetchPermissionTree } from '@/api/permission'
import { useConfirmStore } from '@/store/confirm'
import { useNotificationStore } from '@/store/notification'
import { cn } from '@/utils/cn'
import { Shield, Plus, Search, Pencil, Trash2, ChevronLeft, ChevronRight } from '@lucide/vue'
import RoleDialog from '@/views/pc/admin/components/RoleDialog.vue'
import type { PageResult, PermissionTreeVo, RoleSaveDto, RoleUpdateDto, RoleVo } from '@/types/auth'

const confirmStore = useConfirmStore()
const notificationStore = useNotificationStore()

const query = reactive({
  code: '',
  name: '',
  status: undefined as number | undefined,
  pageNum: 1,
  pageSize: 10,
})

const pageData = ref<PageResult<RoleVo>>({
  records: [],
  total: '0',
  size: '10',
  current: '1',
  pages: '0',
})
const loading = ref(false)
const dialogSubmitting = ref(false)
const statusLoading = ref(false)
const deleteLoading = ref(false)
const permissionTree = ref<PermissionTreeVo[]>([])
const dialogOpen = ref(false)
const isEdit = ref(false)
const editingRole = ref<RoleVo | null>(null)
const dialogForm = reactive<Partial<RoleSaveDto & RoleUpdateDto>>({
  code: '',
  name: '',
  description: '',
  status: 1,
  permissionCodes: [],
})

async function loadRoles() {
  loading.value = true
  try {
    pageData.value = await fetchRolePage(query)
  } finally {
    loading.value = false
  }
}

async function loadPermissions() {
  permissionTree.value = await fetchPermissionTree()
}

function handleSearch() {
  query.pageNum = 1
  loadRoles()
}

function handlePageChange(page: number) {
  query.pageNum = page
  loadRoles()
}

function openCreateDialog() {
  isEdit.value = false
  editingRole.value = null
  dialogForm.code = ''
  dialogForm.name = ''
  dialogForm.description = ''
  dialogForm.status = 1
  dialogForm.permissionCodes = []
  dialogOpen.value = true
}

function openEditDialog(role: RoleVo) {
  isEdit.value = true
  editingRole.value = role
  dialogForm.code = role.code
  dialogForm.name = role.name
  dialogForm.description = role.description
  dialogForm.status = role.status
  dialogForm.permissionCodes = role.permissionCodes ? [...role.permissionCodes] : []
  dialogOpen.value = true
}

async function submitRole(dto: RoleSaveDto | RoleUpdateDto) {
  dialogSubmitting.value = true
  try {
    if (isEdit.value && editingRole.value) {
      await updateRole(editingRole.value.id, dto as RoleUpdateDto)
      notificationStore.success('角色更新成功')
    } else {
      await createRole(dto as RoleSaveDto)
      notificationStore.success('角色创建成功')
    }
    dialogOpen.value = false
    await loadRoles()
  } finally {
    dialogSubmitting.value = false
  }
}

async function handleToggleStatus(role: RoleVo) {
  const next = role.status === 1 ? 0 : 1
  statusLoading.value = true
  try {
    await updateRoleStatus(role.id, { status: next })
    notificationStore.success('状态更新成功')
    await loadRoles()
  } finally {
    statusLoading.value = false
  }
}

async function handleDelete(role: RoleVo) {
  const confirmed = await confirmStore.open({
    title: '删除角色',
    message: `确定要删除角色 ${role.name} 吗？`,
    confirmText: '删除',
    type: 'danger',
  })
  if (!confirmed) return
  deleteLoading.value = true
  try {
    await deleteRole(role.id)
    notificationStore.success('角色删除成功')
    await loadRoles()
  } finally {
    deleteLoading.value = false
  }
}

onMounted(() => {
  loadRoles()
  loadPermissions()
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
            v-model="query.name"
            type="text"
            placeholder="搜索角色名称..."
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

    <!-- 角色列表 -->
    <div class="flex-1 overflow-y-auto p-4">
      <div class="space-y-3">
        <div
          v-for="role in pageData.records"
          :key="role.id"
          class="rounded-2xl border border-surface-200 bg-white p-4 shadow-card"
        >
          <div class="flex items-start justify-between">
            <div class="flex items-center gap-3">
              <div class="flex h-10 w-10 items-center justify-center rounded-xl bg-primary-100 text-primary-600">
                <Shield class="h-5 w-5" />
              </div>
              <div>
                <p class="text-sm font-semibold text-surface-900">
                  {{ role.name }}
                </p>
                <p class="text-xs text-surface-500">
                  {{ role.code }}
                </p>
              </div>
            </div>
            <div
              v-if="role.code !== 'super_admin'"
              class="flex items-center gap-1"
            >
              <button
                class="rounded-lg p-2 text-surface-500 hover:bg-surface-100"
                @click="openEditDialog(role)"
              >
                <Pencil class="h-4 w-4" />
              </button>
              <button
                class="rounded-lg p-2 text-red-500 hover:bg-red-50"
                @click="handleDelete(role)"
              >
                <Trash2 class="h-4 w-4" />
              </button>
            </div>
          </div>

          <p class="mt-2 line-clamp-2 text-xs text-surface-500">
            {{ role.description || '暂无描述' }}
          </p>

          <div class="mt-3 flex items-center justify-between">
            <span
              :class="
                cn(
                  'rounded-full px-2.5 py-0.5 text-xs font-medium',
                  role.status === 1 ? 'bg-emerald-100 text-emerald-700' : 'bg-red-100 text-red-700'
                )
              "
            >
              {{ role.status === 1 ? '启用' : '禁用' }}
            </span>
            <button
              v-if="role.code !== 'super_admin'"
              :disabled="statusLoading"
              class="text-xs font-medium text-surface-600 hover:text-primary-600 disabled:opacity-50"
              @click="handleToggleStatus(role)"
            >
              {{ role.status === 1 ? '禁用' : '启用' }}
            </button>
          </div>
        </div>
      </div>

      <!-- 分页 -->
      <div class="mt-4 flex items-center justify-between">
        <span class="text-xs text-surface-500">共 {{ pageData.total }} 条</span>
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

    <RoleDialog
      v-model:open="dialogOpen"
      :is-edit="isEdit"
      :initial-form="dialogForm"
      :permission-tree="permissionTree"
      :submitting="dialogSubmitting"
      @submit="submitRole"
    />
  </div>
</template>
