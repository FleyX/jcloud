<script setup lang="ts">
import { computed, ref, watch } from 'vue'
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
import { X, Plug, Check } from '@lucide/vue'
import { useNotificationStore } from '@/store/notification'
import { cn } from '@/utils/cn'
import { createRemoteMount, fetchRemoteMountDetail, testRemoteConnection, updateRemoteMount } from '@/api/remote-mount'
import type { RemoteMountSaveDto, RemoteMountType, RemoteMountUpdateDto, RemoteMountVo } from '@/types/remote-mount'

interface Props {
  open: boolean
  editingMount?: RemoteMountVo | null
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:open': [value: boolean]
  success: []
}>()

const notificationStore = useNotificationStore()

const localOpen = computed({
  get: () => props.open,
  set: (value) => emit('update:open', value),
})

const isEdit = computed(() => !!props.editingMount)

const form = ref<RemoteMountSaveDto & { id?: string }>({
  name: '',
  type: 'webdav',
  url: '',
  username: '',
  password: '',
  rootPath: '',
  cronExpr: '',
  enabled: 0,
})

const loading = ref(false)
const submitting = ref(false)
const testing = ref(false)
const testOk = ref<boolean | null>(null)

const typeOptions: { value: RemoteMountType; label: string }[] = [
  { value: 'webdav', label: 'WebDAV' },
  { value: 's3', label: 'S3（预留）' },
  { value: 'nfs', label: 'NFS（预留）' },
]

watch(() => props.open, (open) => {
  if (open) {
    resetForm()
    if (isEdit.value && props.editingMount) {
      loadDetail(props.editingMount.id)
    }
  }
})

function resetForm() {
  form.value = {
    name: '',
    type: 'webdav',
    url: '',
    username: '',
    password: '',
    rootPath: '',
    cronExpr: '',
    enabled: 0,
  }
  testOk.value = null
}

async function loadDetail(id: string) {
  loading.value = true
  try {
    const detail = await fetchRemoteMountDetail(id)
    form.value = {
      id: detail.id,
      name: detail.name,
      type: detail.type,
      url: detail.url,
      username: detail.username || '',
      password: '',
      rootPath: detail.rootPath || '',
      cronExpr: detail.cronExpr || '',
      enabled: detail.enabled,
    }
  } finally {
    loading.value = false
  }
}

function validate(): boolean {
  if (!form.value.name.trim()) {
    notificationStore.error('挂载名称不能为空')
    return false
  }
  if (!form.value.url.trim()) {
    notificationStore.error('URL 不能为空')
    return false
  }
  return true
}

async function handleTestConnection() {
  if (!validate()) return
  testing.value = true
  testOk.value = null
  try {
    await testRemoteConnection(buildSaveDto())
    testOk.value = true
    notificationStore.success('连接成功')
  } catch {
    testOk.value = false
  } finally {
    testing.value = false
  }
}

function buildSaveDto(): RemoteMountSaveDto {
  return {
    name: form.value.name.trim(),
    type: form.value.type,
    url: form.value.url.trim(),
    username: form.value.username?.trim() || undefined,
    password: form.value.password || undefined,
    rootPath: form.value.rootPath?.trim() || undefined,
    cronExpr: form.value.cronExpr?.trim() || undefined,
    enabled: form.value.enabled,
  }
}

async function handleSubmit() {
  if (!validate()) return
  submitting.value = true
  try {
    if (isEdit.value && form.value.id) {
      const dto: RemoteMountUpdateDto = {
        id: form.value.id,
        ...buildSaveDto(),
      }
      await updateRemoteMount(form.value.id, dto)
    } else {
      await createRemoteMount(buildSaveDto())
    }
    notificationStore.success(isEdit.value ? '挂载更新成功' : '挂载创建成功')
    emit('success')
    localOpen.value = false
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <DialogRoot v-model:open="localOpen">
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 z-40 bg-surface-900/40 backdrop-blur-sm" />
      <DialogContent
        class="fixed left-1/2 top-1/2 z-50 w-full max-w-lg -translate-x-1/2 -translate-y-1/2 rounded-2xl bg-white p-6 shadow-soft"
      >
        <div class="mb-4 flex items-center justify-between">
          <div class="flex items-center gap-2">
            <Plug class="h-5 w-5 text-primary-600" />
            <DialogTitle class="text-lg font-bold text-surface-900">
              {{ isEdit ? '编辑远程挂载' : '新增远程挂载' }}
            </DialogTitle>
          </div>
          <DialogClose
            class="rounded-lg p-1 text-surface-400 outline-none hover:bg-surface-100 hover:text-surface-600"
          >
            <X class="h-5 w-5" />
          </DialogClose>
        </div>

        <DialogDescription class="mb-4 text-sm text-surface-500">
          {{ isEdit ? '修改远程挂载配置与同步策略' : '将远程 WebDAV 资源挂载为文件树中的一个顶层目录' }}
        </DialogDescription>

        <div
          v-if="loading"
          class="py-8 text-center text-sm text-surface-500"
        >
          加载中...
        </div>

        <div
          v-else
          class="space-y-4"
        >
          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">挂载名称</label>
            <input
              v-model="form.name"
              type="text"
              placeholder="例如 我的坚果云"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>

          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">协议类型</label>
            <select
              v-model="form.type"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
              <option
                v-for="opt in typeOptions"
                :key="opt.value"
                :value="opt.value"
              >
                {{ opt.label }}
              </option>
            </select>
          </div>

          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">服务器地址</label>
            <input
              v-model="form.url"
              type="text"
              placeholder="https://example.com/dav"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>

          <div class="grid grid-cols-2 gap-4">
            <div>
              <label class="mb-1 block text-xs font-medium text-surface-700">用户名</label>
              <input
                v-model="form.username"
                type="text"
                class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
              >
            </div>
            <div>
              <label class="mb-1 block text-xs font-medium text-surface-700">密码</label>
              <input
                v-model="form.password"
                type="password"
                :placeholder="isEdit ? '不修改请留空' : ''"
                class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
              >
            </div>
          </div>

          <div>
            <label class="mb-1 block text-xs font-medium text-surface-700">根路径</label>
            <input
              v-model="form.rootPath"
              type="text"
              placeholder="/（默认为根目录）"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
          </div>

          <div class="flex items-center justify-between rounded-xl border border-surface-200 bg-surface-50 px-4 py-3">
            <span class="text-sm font-medium text-surface-700">启用定时同步</span>
            <SwitchRoot
              :checked="form.enabled === 1"
              class="relative h-6 w-11 cursor-pointer rounded-full bg-surface-200 outline-none transition-colors data-[state=checked]:bg-primary-600"
              @update:checked="(v: boolean) => form.enabled = v ? 1 : 0"
            >
              <SwitchThumb
                class="block h-5 w-5 translate-x-0.5 rounded-full bg-white shadow-sm transition-transform data-[state=checked]:translate-x-[22px]"
              />
            </SwitchRoot>
          </div>

          <div v-if="form.enabled === 1">
            <label class="mb-1 block text-xs font-medium text-surface-700">Cron 表达式</label>
            <input
              v-model="form.cronExpr"
              type="text"
              placeholder="例如 0 0 2 * * *"
              class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            >
            <p class="mt-1 text-xs text-surface-400">
              参考：每天凌晨 2 点执行可填写 0 0 2 * * *
            </p>
          </div>
        </div>

        <div class="mt-6 flex items-center justify-between gap-3">
          <button
            :disabled="testing || submitting || loading"
            :class="cn(
              'flex items-center gap-1.5 rounded-xl px-4 py-2 text-sm font-medium transition-colors',
              testOk === true && 'bg-emerald-50 text-emerald-600',
              testOk === false && 'bg-red-50 text-red-600',
              testOk === null && 'border border-surface-200 bg-white text-surface-700 hover:bg-surface-50',
              (testing || submitting || loading) && 'cursor-not-allowed opacity-60'
            )"
            @click="handleTestConnection"
          >
            <Check
              v-if="testOk === true"
              class="h-4 w-4"
            />
            {{ testing ? '测试中...' : testOk === true ? '连接正常' : testOk === false ? '连接失败' : '测试连接' }}
          </button>

          <div class="flex items-center gap-2">
            <DialogClose as-child>
              <button
                class="rounded-xl border border-surface-200 bg-white px-4 py-2 text-sm font-medium text-surface-700 transition-colors hover:bg-surface-50"
              >
                取消
              </button>
            </DialogClose>
            <button
              :disabled="submitting || loading"
              class="rounded-xl bg-primary-600 px-4 py-2 text-sm font-medium text-white shadow-soft transition-colors hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-70"
              @click="handleSubmit"
            >
              {{ submitting ? '保存中...' : isEdit ? '保存' : '创建' }}
            </button>
          </div>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
