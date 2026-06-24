<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createPermission,
  deletePermission,
  fetchPermissionTree,
  fetchPermission,
  fetchResources,
  updatePermission,
  updatePermissionStatus,
} from '@/api/permission'
import { useConfirmStore } from '@/store/confirm'
import { useNotificationStore } from '@/store/notification'
import PermissionDialog from './components/PermissionDialog.vue'
import type { PermissionSaveDto, PermissionTreeVo, PermissionUpdateDto, PermissionVo, ResourceVo } from '@/types/auth'
import { Pencil, Plus, Trash2 } from '@lucide/vue'

const tree = ref<PermissionTreeVo[]>([])
const resources = ref<ResourceVo[]>([])
const loading = ref(false)
const dialogSubmitting = ref(false)
const statusLoading = ref(false)
const deleteLoading = ref(false)
const dialogOpen = ref(false)
const isEdit = ref(false)
const editingPermission = ref<PermissionVo | null>(null)
const dialogForm = reactive<Partial<PermissionSaveDto & PermissionUpdateDto>>({
  code: '',
  name: '',
  parentId: undefined,
  status: 1,
  resourceIds: [],
})
const expandedIds = ref<Set<string>>(new Set())

const confirmStore = useConfirmStore()
const notificationStore = useNotificationStore()

async function loadTree() {
  loading.value = true
  try {
    tree.value = await fetchPermissionTree()
  } catch (error) {
    notificationStore.error(error instanceof Error ? error.message : '加载权限树失败')
  } finally {
    loading.value = false
  }
}

async function loadResources() {
  try {
    resources.value = await fetchResources()
  } catch (error) {
    notificationStore.error(error instanceof Error ? error.message : '加载资源失败')
  }
}

function toggleExpand(node: PermissionTreeVo) {
  if (expandedIds.value.has(node.id)) {
    expandedIds.value.delete(node.id)
  } else {
    expandedIds.value.add(node.id)
  }
}

function isExpanded(node: PermissionTreeVo): boolean {
  return expandedIds.value.has(node.id)
}

function openCreateDialog(parentId?: string) {
  isEdit.value = false
  editingPermission.value = null
  dialogForm.code = ''
  dialogForm.name = ''
  dialogForm.parentId = parentId
  dialogForm.status = 1
  dialogForm.resourceIds = []
  dialogOpen.value = true
}

async function openEditDialog(permission: PermissionTreeVo) {
  isEdit.value = true
  try {
    const detail = await fetchPermission(permission.id)
    editingPermission.value = detail
    dialogForm.code = detail.code
    dialogForm.name = detail.name
    dialogForm.parentId = detail.parentId
    dialogForm.status = detail.status
    dialogForm.resourceIds = detail.resourceIds ? [...detail.resourceIds] : []
    dialogOpen.value = true
  } catch (error) {
    notificationStore.error(error instanceof Error ? error.message : '加载权限详情失败')
  }
}

async function submitPermission(dto: PermissionSaveDto | PermissionUpdateDto) {
  dialogSubmitting.value = true
  try {
    if (isEdit.value && editingPermission.value) {
      await updatePermission(editingPermission.value.id, dto as PermissionUpdateDto)
      notificationStore.success('权限更新成功')
    } else {
      await createPermission(dto as PermissionSaveDto)
      notificationStore.success('权限创建成功')
    }
    dialogOpen.value = false
    await loadTree()
  } catch (error) {
    notificationStore.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    dialogSubmitting.value = false
  }
}

async function handleToggleStatus(permission: PermissionTreeVo) {
  const next = permission.status === 1 ? 0 : 1
  statusLoading.value = true
  try {
    await updatePermissionStatus(permission.id, { status: next })
    notificationStore.success('状态更新成功')
    await loadTree()
  } catch (error) {
    notificationStore.error(error instanceof Error ? error.message : '状态更新失败')
  } finally {
    statusLoading.value = false
  }
}

async function handleDelete(permission: PermissionTreeVo) {
  const confirmed = await confirmStore.open({
    title: '删除权限',
    message: `确定要删除权限 ${permission.name} 吗？`,
    confirmText: '删除',
    type: 'danger',
  })
  if (!confirmed) return
  deleteLoading.value = true
  try {
    await deletePermission(permission.id)
    notificationStore.success('权限删除成功')
    await loadTree()
  } catch (error) {
    notificationStore.error(error instanceof Error ? error.message : '删除失败')
  } finally {
    deleteLoading.value = false
  }
}

function flattenNodes(nodes: PermissionTreeVo[], level = 0): Array<{ node: PermissionTreeVo; level: number }> {
  const result: Array<{ node: PermissionTreeVo; level: number }> = []
  for (const node of nodes) {
    result.push({ node, level })
    if (node.children && node.children.length > 0 && isExpanded(node)) {
      result.push(...flattenNodes(node.children, level + 1))
    }
  }
  return result
}

const flattenedTree = computed(() => flattenNodes(tree.value))

onMounted(() => {
  loadTree()
  loadResources()
})
</script>

<template>
  <div class="flex h-full flex-col gap-5">
    <div class="flex flex-col gap-4 rounded-2xl border border-surface-200 bg-white p-5 shadow-card sm:flex-row sm:items-center sm:justify-between">
      <div>
        <h2 class="text-lg font-bold text-surface-900">权限管理</h2>
        <p class="text-xs text-surface-500">管理系统权限及资源分配</p>
      </div>
      <button class="flex items-center gap-1 rounded-xl bg-emerald-600 px-4 py-2 text-sm font-medium text-white" @click="openCreateDialog()">
        <Plus class="h-4 w-4" /> 新增权限
      </button>
    </div>

    <div class="flex-1 overflow-hidden rounded-2xl border border-surface-200 bg-white shadow-card">
      <table class="w-full text-left text-sm">
        <thead class="bg-surface-50 text-xs uppercase text-surface-500">
          <tr>
            <th class="px-5 py-3">权限编码</th>
            <th class="px-5 py-3">权限名称</th>
            <th class="px-5 py-3">父级</th>
            <th class="px-5 py-3">状态</th>
            <th class="px-5 py-3">操作</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-surface-100">
          <tr v-for="{ node, level } in flattenedTree" :key="node.id" class="hover:bg-surface-50/50">
            <td class="px-5 py-3 font-medium text-surface-900" :style="{ paddingLeft: `${1.25 + level * 1.5}rem` }">
              <button
                v-if="node.children && node.children.length > 0"
                class="mr-1 inline-flex h-5 w-5 items-center justify-center rounded text-surface-500 hover:bg-surface-100"
                @click="toggleExpand(node)"
              >
                {{ isExpanded(node) ? '−' : '+' }}
              </button>
              <span v-else class="mr-1 inline-block h-5 w-5"></span>
              {{ node.code }}
            </td>
            <td class="px-5 py-3">{{ node.name }}</td>
            <td class="px-5 py-3 text-surface-500">{{ node.parentId || '-' }}</td>
            <td class="px-5 py-3">
              <span :class="node.status === 1 ? 'text-emerald-600' : 'text-red-600'">{{ node.status === 1 ? '启用' : '禁用' }}</span>
            </td>
            <td class="px-5 py-3">
              <div class="flex items-center gap-2">
                <button class="rounded-lg bg-surface-100 px-2.5 py-1.5 text-xs" @click="openEditDialog(node)">
                  <Pencil class="inline h-3.5 w-3.5" /> 编辑
                </button>
                <button class="rounded-lg bg-surface-100 px-2.5 py-1.5 text-xs" @click="openCreateDialog(node.id)">
                  <Plus class="inline h-3.5 w-3.5" /> 新增子权限
                </button>
                <button class="rounded-lg bg-surface-100 px-2.5 py-1.5 text-xs" @click="handleToggleStatus(node)">
                  {{ node.status === 1 ? '禁用' : '启用' }}
                </button>
                <button class="rounded-lg bg-red-50 px-2.5 py-1.5 text-xs text-red-600" @click="handleDelete(node)">
                  <Trash2 class="inline h-3.5 w-3.5" /> 删除
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <PermissionDialog
      v-model:open="dialogOpen"
      :is-edit="isEdit"
      :initial-form="dialogForm"
      :resources="resources"
      :submitting="dialogSubmitting"
      @submit="submitPermission"
    />
  </div>
</template>
