<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { DialogContent, DialogDescription, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'radix-vue'
import type { PermissionSaveDto, PermissionUpdateDto, ResourceVo } from '@/types/auth'

const props = defineProps<{
  open: boolean
  isEdit: boolean
  initialForm?: Partial<PermissionSaveDto & PermissionUpdateDto>
  resources: ResourceVo[]
  submitting?: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  submit: [dto: PermissionSaveDto | PermissionUpdateDto]
}>()

const localOpen = computed({
  get: () => props.open,
  set: (val) => emit('update:open', val),
})

type PermissionForm = PermissionSaveDto & PermissionUpdateDto

const form = reactive<PermissionForm>({
  code: '',
  name: '',
  parentId: undefined,
  status: 1,
  resourceIds: [],
})

watch(
  () => props.open,
  (open) => {
    if (open) {
      form.code = props.initialForm?.code ?? ''
      form.name = props.initialForm?.name ?? ''
      form.parentId = props.initialForm?.parentId
      form.status = props.initialForm?.status ?? 1
      form.resourceIds = props.initialForm?.resourceIds ? [...props.initialForm.resourceIds] : []
    }
  },
)

function handleSubmit() {
  const dto = props.isEdit
    ? ({
        name: form.name,
        parentId: form.parentId,
        status: form.status,
        resourceIds: form.resourceIds,
      } as PermissionUpdateDto)
    : ({
        code: form.code,
        name: form.name,
        parentId: form.parentId,
        status: form.status,
        resourceIds: form.resourceIds,
      } as PermissionSaveDto)
  emit('submit', dto)
}
</script>

<template>
  <DialogRoot v-model:open="localOpen">
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 bg-black/40" />
      <DialogContent class="fixed left-1/2 top-1/2 w-full max-w-xl -translate-x-1/2 -translate-y-1/2 rounded-2xl bg-white p-6 shadow-xl">
        <DialogTitle class="text-lg font-bold">{{ isEdit ? '编辑权限' : '新增权限' }}</DialogTitle>
        <DialogDescription class="mt-1 text-sm text-surface-500">{{ isEdit ? '修改权限信息与资源' : '创建新权限' }}</DialogDescription>

        <div v-if="isEdit" class="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-700">
          非必要请勿修改，避免系统出错
        </div>

        <div class="mt-4 space-y-4">
          <div>
            <label class="mb-1 block text-sm font-medium">权限编码</label>
            <input
              v-model="form.code"
              :disabled="isEdit"
              type="text"
              class="w-full rounded-xl border border-surface-200 px-3 py-2 text-sm disabled:bg-surface-100"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">权限名称</label>
            <input v-model="form.name" type="text" class="w-full rounded-xl border border-surface-200 px-3 py-2 text-sm" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">父级权限 ID</label>
            <input v-model="form.parentId" type="text" class="w-full rounded-xl border border-surface-200 px-3 py-2 text-sm" placeholder="无父级请留空" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">状态</label>
            <select v-model="form.status" class="w-full rounded-xl border border-surface-200 px-3 py-2 text-sm">
              <option :value="1">启用</option>
              <option :value="0">禁用</option>
            </select>
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">资源</label>
            <div class="max-h-48 overflow-y-auto rounded-xl border border-surface-200 p-3">
              <label v-for="res in resources" :key="res.id" class="flex items-center gap-2 py-1">
                <input v-model="form.resourceIds" type="checkbox" :value="res.id" class="h-4 w-4 rounded border-surface-300 text-primary-600" />
                <span class="text-sm text-surface-700">{{ res.name }}</span>
                <span class="text-xs text-surface-400">({{ res.code }})</span>
              </label>
            </div>
          </div>
        </div>

        <div class="mt-6 flex justify-end gap-2">
          <button class="rounded-xl px-4 py-2 text-sm font-medium text-surface-600 hover:bg-surface-100" @click="localOpen = false">取消</button>
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
