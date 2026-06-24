<script setup lang="ts">
/**
 * 移动端权限管理
 * - 树形列表展示权限，复用 PC 端的 PermissionDialog 弹窗组件
 */
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
import { cn } from '@/utils/cn'
import { Lock, Plus, Pencil, Trash2 } from '@lucide/vue'
import PermissionDialog from '@/views/pc/admin/components/PermissionDialog.vue'
import type { PermissionSaveDto, PermissionTreeVo, PermissionUpdateDto, ResourceVo } from '@/types/auth'

const confirmStore = useConfirmStore()
const notificationStore = useNotificationStore()

const tree = ref<PermissionTreeVo[]>([])
const resources = ref<ResourceVo[]>([])
const loading = ref(false)
const dialogSubmitting = ref(false)
const statusLoading = ref(false)
const deleteLoading = ref(false)
const dialogOpen = ref(false)
const isEdit = ref(false)
const editingPermission = ref<PermissionTreeVo | null>(null)
const dialogForm = reactive<Partial<PermissionSaveDto & PermissionUpdateDto>>({
  code: '',
  name: '',
  parentId: undefined,
  status: 1,
  resourceIds: [],
})
const expandedIds = ref<Set<string>>(new Set())

async function loadTree() {
  loading.value = true
  try {
    tree.value = await fetchPermissionTree()
  } finally {
    loading.value = false
  }
}

async function loadResources() {
  resources.value = await fetchResources()
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
  } catch {
    // request.ts 已统一处理异常提示
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
  <div class="flex h-full flex-col bg-surface-50">
    <!-- 顶部工具栏 -->
    <div class="sticky top-0 z-10 border-b border-surface-200 bg-white/90 px-4 py-3 backdrop-blur-md">
      <div class="flex items-center justify-between">
        <h2 class="text-base font-bold text-surface-900">
          权限管理
        </h2>
        <button
          class="flex h-9 w-9 items-center justify-center rounded-xl bg-primary-600 text-white shadow-soft active:scale-95"
          @click="openCreateDialog()"
        >
          <Plus class="h-5 w-5" />
        </button>
      </div>
    </div>

    <!-- 权限树列表 -->
    <div class="flex-1 overflow-y-auto p-4">
      <div class="space-y-2">
        <div
          v-for="{ node, level } in flattenedTree"
          :key="node.id"
          :style="{ paddingLeft: `${level * 1.25}rem` }"
          class="rounded-xl border border-surface-200 bg-white p-3 shadow-sm"
        >
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-2">
              <button
                v-if="node.children && node.children.length > 0"
                class="flex h-6 w-6 items-center justify-center rounded-md text-surface-500 hover:bg-surface-100"
                @click="toggleExpand(node)"
              >
                {{ isExpanded(node) ? '−' : '+' }}
              </button>
              <span
                v-else
                class="h-6 w-6"
              />
              <div class="flex h-8 w-8 items-center justify-center rounded-lg bg-primary-100 text-primary-600">
                <Lock class="h-4 w-4" />
              </div>
              <div>
                <p class="text-sm font-semibold text-surface-900">
                  {{ node.name }}
                </p>
                <p class="text-xs text-surface-500">
                  {{ node.code }}
                </p>
              </div>
            </div>
            <div class="flex items-center gap-1">
              <button
                class="rounded-lg p-2 text-surface-500 hover:bg-surface-100"
                @click="openEditDialog(node)"
              >
                <Pencil class="h-4 w-4" />
              </button>
              <button
                class="rounded-lg p-2 text-red-500 hover:bg-red-50"
                @click="handleDelete(node)"
              >
                <Trash2 class="h-4 w-4" />
              </button>
            </div>
          </div>

          <div class="mt-2 flex items-center justify-between pl-14">
            <span
              :class="
                cn(
                  'rounded-full px-2.5 py-0.5 text-xs font-medium',
                  node.status === 1 ? 'bg-emerald-100 text-emerald-700' : 'bg-red-100 text-red-700'
                )
              "
            >
              {{ node.status === 1 ? '启用' : '禁用' }}
            </span>
            <button
              :disabled="statusLoading"
              class="text-xs font-medium text-surface-600 hover:text-primary-600 disabled:opacity-50"
              @click="handleToggleStatus(node)"
            >
              {{ node.status === 1 ? '禁用' : '启用' }}
            </button>
          </div>
        </div>
      </div>
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
