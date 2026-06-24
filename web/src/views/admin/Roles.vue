<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  createRole,
  deleteRole,
  fetchRolePage,
  updateRole,
  updateRoleStatus,
} from '@/api/role'
import { fetchPermissionTree } from '@/api/permission'
import { cn } from '@/utils/cn'
import { useConfirmStore } from '@/store/confirm'
import { useNotificationStore } from '@/store/notification'
import RoleDialog from './components/RoleDialog.vue'
import type { PageResult, PermissionTreeVo, RoleSaveDto, RoleUpdateDto, RoleVo } from '@/types/auth'
import { ChevronLeft, ChevronRight, Pencil, Plus, Trash2 } from '@lucide/vue'

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
  permissionIds: [],
})

const confirmStore = useConfirmStore()
const notificationStore = useNotificationStore()

async function loadRoles() {
  loading.value = true
  try {
    pageData.value = await fetchRolePage(query)
  } catch {
    // request.ts 已统一处理异常提示
  } finally {
    loading.value = false
  }
}

async function loadPermissions() {
  try {
    permissionTree.value = await fetchPermissionTree()
  } catch {
    // request.ts 已统一处理异常提示
  }
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
  dialogForm.permissionIds = []
  dialogOpen.value = true
}

function openEditDialog(role: RoleVo) {
  isEdit.value = true
  editingRole.value = role
  dialogForm.code = role.code
  dialogForm.name = role.name
  dialogForm.description = role.description
  dialogForm.status = role.status
  dialogForm.permissionIds = role.permissionIds ? [...role.permissionIds] : []
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
  } catch {
    // request.ts 已统一处理异常提示
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
  } catch {
    // request.ts 已统一处理异常提示
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
  } catch {
    // request.ts 已统一处理异常提示
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
  <div class="flex h-full flex-col gap-5">
    <div class="flex flex-col gap-4 rounded-2xl border border-surface-200 bg-white p-5 shadow-card sm:flex-row sm:items-center sm:justify-between">
      <div>
        <h2 class="text-lg font-bold text-surface-900">角色管理</h2>
        <p class="text-xs text-surface-500">管理系统角色及其权限分配</p>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <input v-model="query.code" type="text" placeholder="编码" class="rounded-xl border border-surface-200 px-3 py-2 text-sm" />
        <input v-model="query.name" type="text" placeholder="名称" class="rounded-xl border border-surface-200 px-3 py-2 text-sm" />
        <select v-model="query.status" class="rounded-xl border border-surface-200 px-3 py-2 text-sm">
          <option :value="undefined">全部状态</option>
          <option :value="1">启用</option>
          <option :value="0">禁用</option>
        </select>
        <button class="rounded-xl bg-primary-600 px-4 py-2 text-sm font-medium text-white" @click="handleSearch">查询</button>
        <button class="flex items-center gap-1 rounded-xl bg-emerald-600 px-4 py-2 text-sm font-medium text-white" @click="openCreateDialog">
          <Plus class="h-4 w-4" /> 新增角色
        </button>
      </div>
    </div>

    <div class="flex-1 overflow-hidden rounded-2xl border border-surface-200 bg-white shadow-card">
      <table class="w-full text-left text-sm">
        <thead class="bg-surface-50 text-xs uppercase text-surface-500">
          <tr>
            <th class="px-5 py-3">编码</th>
            <th class="px-5 py-3">名称</th>
            <th class="px-5 py-3">描述</th>
            <th class="px-5 py-3">状态</th>
            <th class="px-5 py-3">操作</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-surface-100">
          <tr v-for="role in pageData.records" :key="role.id" class="hover:bg-surface-50/50">
            <td class="px-5 py-3 font-medium text-surface-900">{{ role.code }}</td>
            <td class="px-5 py-3">{{ role.name }}</td>
            <td class="px-5 py-3 text-surface-500">{{ role.description || '-' }}</td>
            <td class="px-5 py-3">
              <span :class="role.status === 1 ? 'text-emerald-600' : 'text-red-600'">{{ role.status === 1 ? '启用' : '禁用' }}</span>
            </td>
            <td class="px-5 py-3">
              <div class="flex items-center gap-2">
                <button class="rounded-lg bg-surface-100 px-2.5 py-1.5 text-xs" @click="openEditDialog(role)">
                  <Pencil class="inline h-3.5 w-3.5" /> 编辑
                </button>
                <button
                  :disabled="statusLoading"
                  class="rounded-lg bg-surface-100 px-2.5 py-1.5 text-xs disabled:cursor-not-allowed disabled:opacity-60"
                  @click="handleToggleStatus(role)"
                >
                  {{ role.status === 1 ? '禁用' : '启用' }}
                </button>
                <button
                  :disabled="deleteLoading"
                  class="rounded-lg bg-red-50 px-2.5 py-1.5 text-xs text-red-600 disabled:cursor-not-allowed disabled:opacity-60"
                  @click="handleDelete(role)"
                >
                  <Trash2 class="inline h-3.5 w-3.5" /> 删除
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>

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
