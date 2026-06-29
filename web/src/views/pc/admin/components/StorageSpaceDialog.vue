<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { StorageSpaceSaveDto, StorageSpaceUpdateDto, StorageSpaceVo } from '@/types/storage-space'
import { Database, X } from '@lucide/vue'
import {
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
  DialogTitle,
  SwitchRoot,
  SwitchThumb,
} from 'radix-vue'

interface Props {
  open: boolean
  editingSpace: StorageSpaceVo | null
  submitting: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:open': [value: boolean]
  submit: [dto: StorageSpaceSaveDto | StorageSpaceUpdateDto]
}>()

const isEdit = computed(() => props.editingSpace !== null)

const form = ref<StorageSpaceSaveDto & { id?: string; status?: number; isPrimary?: number }>({
  name: '',
  path: '',
  remark: '',
  status: 1,
  isPrimary: 0,
})

watch(() => props.editingSpace, (space) => {
  if (space) {
    form.value = {
      id: space.id,
      name: space.name,
      path: space.path,
      remark: space.remark || '',
      status: space.status,
      isPrimary: space.isPrimary,
    }
  } else {
    form.value = {
      name: '',
      path: '',
      remark: '',
      status: 1,
      isPrimary: 0,
    }
  }
}, { immediate: true })

const localOpen = computed({
  get: () => props.open,
  set: (value) => emit('update:open', value),
})

function handleSubmit() {
  if (isEdit.value && form.value.id !== undefined && form.value.status !== undefined && form.value.isPrimary !== undefined) {
    const dto: StorageSpaceUpdateDto = {
      id: form.value.id,
      name: form.value.name,
      path: form.value.path,
      status: form.value.status,
      isPrimary: form.value.isPrimary,
      remark: form.value.remark,
    }
    emit('submit', dto)
  } else {
    const dto: StorageSpaceSaveDto = {
      name: form.value.name,
      path: form.value.path,
      remark: form.value.remark,
    }
    emit('submit', dto)
  }
}
</script>

<template>
  <DialogRoot v-model:open="localOpen">
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 z-40 bg-surface-900/40 backdrop-blur-sm" />
      <DialogContent
        class="fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2 rounded-2xl bg-white p-6 shadow-soft"
      >
        <div class="mb-4 flex items-center justify-between">
          <div class="flex items-center gap-2">
            <Database class="h-5 w-5 text-primary-600" />
            <DialogTitle class="text-lg font-bold text-surface-900">
              {{ isEdit ? '编辑存储空间' : '新增存储空间' }}
            </DialogTitle>
          </div>
          <DialogClose
            class="rounded-lg p-1 text-surface-400 outline-none hover:bg-surface-100 hover:text-surface-600"
          >
            <X class="h-5 w-5" />
          </DialogClose>
        </div>
        <DialogDescription class="mb-4 text-sm text-surface-500">
          {{ isEdit ? '修改存储空间配置信息' : '请输入新存储空间的基本信息，容量由系统自动探测' }}
        </DialogDescription>

        <div class="mb-6 space-y-4">
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">名称</label>
            <input
              v-model="form.name"
              type="text"
              placeholder="请输入存储空间名称"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">物理路径</label>
            <input
              v-model="form.path"
              type="text"
              placeholder="例如 /data/jcloud"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">设为主存储空间</label>
            <div class="flex items-center gap-3">
              <SwitchRoot
                :checked="form.isPrimary === 1"
                class="relative h-6 w-11 cursor-pointer rounded-full bg-surface-200 outline-none transition-colors data-[state=checked]:bg-primary-600"
                @update:checked="(checked: boolean) => (form.isPrimary = checked ? 1 : 0)"
              >
                <SwitchThumb
                  class="block h-5 w-5 translate-x-0.5 rounded-full bg-white shadow-sm transition-transform data-[state=checked]:translate-x-[22px]"
                />
              </SwitchRoot>
              <span class="text-sm text-surface-700">{{ form.isPrimary === 1 ? '是' : '否' }}</span>
            </div>
          </div>
          <div v-if="isEdit">
            <label class="mb-1 block text-xs font-medium text-surface-700">状态</label>
            <div class="flex items-center gap-3">
              <SwitchRoot
                :checked="form.status === 1"
                class="relative h-6 w-11 cursor-pointer rounded-full bg-surface-200 outline-none transition-colors data-[state=checked]:bg-primary-600"
                @update:checked="(checked: boolean) => (form.status = checked ? 1 : 0)"
              >
                <SwitchThumb
                  class="block h-5 w-5 translate-x-0.5 rounded-full bg-white shadow-sm transition-transform data-[state=checked]:translate-x-[22px]"
                />
              </SwitchRoot>
              <span class="text-sm text-surface-700">{{ form.status === 1 ? '启用' : '禁用' }}</span>
            </div>
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">备注</label>
            <input
              v-model="form.remark"
              type="text"
              placeholder="请输入备注"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>
        </div>

        <div class="flex justify-end gap-3">
          <DialogClose as-child>
            <button
              :disabled="submitting"
              class="rounded-xl border border-surface-200 bg-white px-4 py-2 text-sm font-medium text-surface-700 transition-colors hover:bg-surface-50 disabled:cursor-not-allowed disabled:opacity-60"
            >
              取消
            </button>
          </DialogClose>
          <button
            :disabled="submitting"
            class="rounded-xl bg-primary-600 px-4 py-2 text-sm font-medium text-white shadow-soft transition-colors hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-70"
            @click="handleSubmit"
          >
            {{ submitting ? '保存中...' : '保存' }}
          </button>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
