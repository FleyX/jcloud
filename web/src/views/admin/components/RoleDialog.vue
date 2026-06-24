<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { DialogContent, DialogDescription, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'radix-vue'
import type { PermissionTreeVo, RoleSaveDto, RoleUpdateDto } from '@/types/auth'
import PermissionTree from './PermissionTree.vue'

const props = defineProps<{
  open: boolean
  isEdit: boolean
  initialForm?: Partial<RoleSaveDto & RoleUpdateDto>
  permissionTree: PermissionTreeVo[]
  submitting?: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  submit: [dto: RoleSaveDto | RoleUpdateDto]
}>()

type RoleForm = {
  code: string
  name: string
  description?: string
  status?: number
  permissionIds: string[]
}

const form = reactive<RoleForm>({
  code: '',
  name: '',
  description: '',
  status: 1,
  permissionIds: [],
})

const localOpen = computed({
  get: () => props.open,
  set: (value) => emit('update:open', value),
})

watch(
  () => props.open,
  (open) => {
    if (open) {
      form.code = props.initialForm?.code ?? ''
      form.name = props.initialForm?.name ?? ''
      form.description = props.initialForm?.description ?? ''
      form.status = props.initialForm?.status ?? 1
      form.permissionIds = props.initialForm?.permissionIds ? [...props.initialForm.permissionIds] : []
    }
  },
)

function handleSubmit() {
  const dto = props.isEdit
    ? ({
        name: form.name,
        description: form.description,
        status: form.status,
        permissionIds: form.permissionIds,
      } as RoleUpdateDto)
    : ({
        code: form.code,
        name: form.name,
        description: form.description,
        status: form.status,
        permissionIds: form.permissionIds,
      } as RoleSaveDto)
  emit('submit', dto)
}
</script>

<template>
  <DialogRoot v-model:open="localOpen">
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 bg-black/40" />
      <DialogContent
        class="fixed left-1/2 top-1/2 w-full max-w-xl -translate-x-1/2 -translate-y-1/2 rounded-2xl bg-white p-6 shadow-xl"
      >
        <DialogTitle class="text-lg font-bold">{{ isEdit ? '编辑角色' : '新增角色' }}</DialogTitle>
        <DialogDescription class="mt-1 text-sm text-surface-500">{{ isEdit ? '修改角色信息与权限' : '创建新角色' }}</DialogDescription>

        <div class="mt-4 space-y-4">
          <div>
            <label class="mb-1 block text-sm font-medium">角色编码</label>
            <input
              v-model="form.code"
              :disabled="isEdit"
              type="text"
              class="w-full rounded-xl border border-surface-200 px-3 py-2 text-sm outline-none focus:border-primary-500 disabled:bg-surface-100"
              placeholder="如 admin"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">角色名称</label>
            <input
              v-model="form.name"
              type="text"
              class="w-full rounded-xl border border-surface-200 px-3 py-2 text-sm outline-none focus:border-primary-500"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">描述</label>
            <input
              v-model="form.description"
              type="text"
              class="w-full rounded-xl border border-surface-200 px-3 py-2 text-sm outline-none focus:border-primary-500"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">状态</label>
            <select v-model="form.status" class="w-full rounded-xl border border-surface-200 px-3 py-2 text-sm">
              <option :value="1">启用</option>
              <option :value="0">禁用</option>
            </select>
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">权限</label>
            <div class="max-h-64 overflow-y-auto rounded-xl border border-surface-200 p-3">
              <PermissionTree v-model:checked-ids="form.permissionIds" :tree="permissionTree" />
            </div>
          </div>
        </div>

        <div class="mt-6 flex justify-end gap-2">
          <button
            class="rounded-xl px-4 py-2 text-sm font-medium text-surface-600 hover:bg-surface-100"
            @click="$emit('update:open', false)"
          >
            取消
          </button>
          <button
            :disabled="submitting"
            class="rounded-xl bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700 disabled:opacity-60"
            @click="handleSubmit"
          >
            {{ submitting ? '保存中...' : '保存' }}
          </button>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
