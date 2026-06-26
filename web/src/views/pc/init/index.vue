<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { useNotificationStore } from '@/store/notification'
import { fetchInitStatus, initializeSystem } from '@/api/storage-space'
import { cn } from '@/utils/cn'
import { Cloud, Plus, Trash2 } from '@lucide/vue'
import type { InitSpaceItem, SystemInitDto } from '@/types/storage-space'

const router = useRouter()
const userStore = useUserStore()
const notificationStore = useNotificationStore()

const loading = ref(false)
const submitting = ref(false)

const spaces = reactive<InitSpaceItem[]>([
  { name: '默认空间', path: '/data/jcloud', remark: '用户默认主存储空间' },
])

const selected = reactive({
  primaryIndex: 0,
  systemDataIndex: 0,
})

const canSubmit = computed(() =>
  spaces.length > 0
  && spaces.every((s) => s.name.trim() && s.path.trim())
  && selected.primaryIndex >= 0
  && selected.primaryIndex < spaces.length
  && selected.systemDataIndex >= 0
  && selected.systemDataIndex < spaces.length,
)

onMounted(async () => {
  if (!userStore.token) {
    router.replace('/login')
    return
  }
  loading.value = true
  try {
    const status = await fetchInitStatus()
    if (status.initialized) {
      userStore.initialized = true
      router.replace('/files')
    }
  } catch {
    // request.ts 已统一处理异常提示
  } finally {
    loading.value = false
  }
})

function addSpace() {
  spaces.push({ name: '', path: '', remark: '' })
  if (selected.primaryIndex < 0) {
    selected.primaryIndex = 0
  }
  if (selected.systemDataIndex < 0) {
    selected.systemDataIndex = 0
  }
}

function removeSpace(index: number) {
  if (spaces.length <= 1) {
    notificationStore.error('至少保留一个存储空间')
    return
  }
  spaces.splice(index, 1)
  if (selected.primaryIndex >= spaces.length) {
    selected.primaryIndex = spaces.length - 1
  }
  if (selected.systemDataIndex >= spaces.length) {
    selected.systemDataIndex = spaces.length - 1
  }
}

async function handleSubmit() {
  if (!canSubmit.value) {
    notificationStore.error('请完善存储空间信息')
    return
  }
  submitting.value = true
  try {
    const dto: SystemInitDto = {
      spaces: spaces.map((s) => ({
        name: s.name.trim(),
        path: s.path.trim(),
        remark: s.remark?.trim(),
      })),
      primaryIndex: selected.primaryIndex,
      systemDataIndex: selected.systemDataIndex,
    }
    await initializeSystem(dto)
    userStore.initialized = true
    notificationStore.success('系统初始化成功')
    router.replace('/files')
  } catch {
    // request.ts 已统一处理异常提示
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="flex h-screen w-screen items-center justify-center bg-gradient-to-br from-surface-50 to-primary-50 p-4">
    <div class="w-full max-w-2xl rounded-3xl bg-white p-8 shadow-soft">
      <div class="mb-8 flex flex-col items-center gap-3">
        <div class="flex h-14 w-14 items-center justify-center rounded-2xl bg-primary-600 text-white shadow-soft">
          <Cloud class="h-7 w-7" />
        </div>
        <h1 class="text-2xl font-bold text-surface-900">
          系统初始化
        </h1>
        <p class="text-center text-sm text-surface-500">
          首次使用需要配置存储空间，请选择主存储空间及系统数据存放位置
        </p>
      </div>

      <div
        v-if="loading"
        class="py-12 text-center text-sm text-surface-500"
      >
        加载中...
      </div>

      <div v-else>
        <div class="mb-4 flex items-center justify-between">
          <h2 class="text-sm font-semibold text-surface-700">
            存储空间配置
          </h2>
          <button
            class="flex items-center gap-1 rounded-lg bg-primary-50 px-3 py-1.5 text-xs font-medium text-primary-600 transition-colors hover:bg-primary-100"
            @click="addSpace"
          >
            <Plus class="h-3.5 w-3.5" />
            添加空间
          </button>
        </div>

        <div class="mb-6 space-y-3">
          <div
            v-for="(space, index) in spaces"
            :key="index"
            class="rounded-2xl border border-surface-200 p-4"
          >
            <div class="mb-3 flex items-center justify-between">
              <span class="text-xs font-medium text-surface-500">
                空间 {{ index + 1 }}
              </span>
              <button
                class="text-surface-400 transition-colors hover:text-red-500"
                @click="removeSpace(index)"
              >
                <Trash2 class="h-4 w-4" />
              </button>
            </div>

            <div class="grid gap-3 sm:grid-cols-2">
              <div>
                <label class="mb-1 block text-xs font-medium text-surface-700">名称</label>
                <input
                  v-model="space.name"
                  type="text"
                  placeholder="例如：默认空间"
                  class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
                >
              </div>
              <div>
                <label class="mb-1 block text-xs font-medium text-surface-700">物理路径</label>
                <input
                  v-model="space.path"
                  type="text"
                  placeholder="例如 /data/jcloud"
                  class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
                >
              </div>
            </div>

            <div class="mt-3">
              <label class="mb-1 block text-xs font-medium text-surface-700">备注</label>
              <input
                v-model="space.remark"
                type="text"
                placeholder="可选"
                class="w-full rounded-xl border border-surface-200 bg-surface-50 px-3 py-2 text-sm outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
              >
            </div>

            <div class="mt-3 flex flex-wrap gap-4">
              <label class="flex cursor-pointer items-center gap-2 text-sm text-surface-700">
                <input
                  v-model="selected.primaryIndex"
                  type="radio"
                  :value="index"
                  class="h-4 w-4 border-surface-300 text-primary-600 focus:ring-primary-500"
                >
                设为主存储空间
              </label>
              <label class="flex cursor-pointer items-center gap-2 text-sm text-surface-700">
                <input
                  v-model="selected.systemDataIndex"
                  type="radio"
                  :value="index"
                  class="h-4 w-4 border-surface-300 text-primary-600 focus:ring-primary-500"
                >
                系统数据存放于此
              </label>
            </div>
          </div>
        </div>

        <button
          :disabled="submitting || !canSubmit"
          :class="cn(
            'w-full rounded-xl bg-primary-600 py-3 text-sm font-semibold text-white shadow-soft transition-all hover:bg-primary-700 hover:shadow-md',
            (submitting || !canSubmit) && 'cursor-not-allowed opacity-70',
          )"
          @click="handleSubmit"
        >
          {{ submitting ? '初始化中...' : '完成初始化' }}
        </button>
      </div>
    </div>
  </div>
</template>
