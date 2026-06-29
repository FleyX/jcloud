<script setup lang="ts">
/**
 * 文件列表顶部工具栏：面包屑、搜索、新建文件夹、上传
 */
import { ref } from 'vue'
import {
  ChevronRight,
  Upload,
  FileUp,
  FolderUp,
  Plus,
  FolderPlus,
  Search,
  X,
} from '@lucide/vue'
import { cn } from '@/utils/cn'
import {
  DropdownMenuRoot,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
} from 'radix-vue'

interface Props {
  breadcrumbStack: Array<{ id: string; name: string }>
  keyword: string
  isSearching: boolean
}

defineProps<Props>()
const emit = defineEmits<{
  'update:keyword': [value: string]
  navigateToBreadcrumb: [index: number]
  search: []
  clearSearch: []
  createFolder: []
  mockUpload: []
  fileChange: [event: Event]
}>()

const fileInput = ref<HTMLInputElement | null>(null)

function triggerFileSelect() {
  fileInput.value?.click()
}
</script>

<template>
  <div class="mb-6 flex items-center justify-between">
    <!-- 面包屑导航 -->
    <nav class="flex items-center gap-1 text-sm">
      <span
        v-for="(crumb, index) in breadcrumbStack"
        :key="crumb.id"
        class="flex items-center gap-1"
      >
        <button
          :class="
            cn(
              'font-medium',
              index === breadcrumbStack.length - 1 ? 'font-semibold text-surface-900' : 'text-surface-500 hover:text-surface-700'
            )
          "
          :disabled="index === breadcrumbStack.length - 1"
          @click="emit('navigateToBreadcrumb', index)"
        >
          {{ crumb.name }}
        </button>
        <ChevronRight
          v-if="index < breadcrumbStack.length - 1"
          class="h-4 w-4 text-surface-300"
        />
      </span>
    </nav>

    <div class="flex items-center gap-3">
      <!-- 搜索框 -->
      <div
        class="flex h-9 items-center gap-2 rounded-xl border border-surface-200 bg-white px-3 shadow-card transition-shadow duration-200 focus-within:border-primary-300 focus-within:ring-2 focus-within:ring-primary-100"
      >
        <Search class="h-4 w-4 text-surface-400" />
        <input
          :value="keyword"
          type="text"
          placeholder="搜索文件..."
          class="w-48 bg-transparent text-sm outline-none placeholder:text-surface-400"
          @input="$emit('update:keyword', ($event.target as HTMLInputElement).value)"
          @keyup.enter="emit('search')"
        >
        <button
          v-if="isSearching"
          class="rounded p-0.5 text-surface-400 hover:bg-surface-100 hover:text-surface-600"
          @click="emit('clearSearch')"
        >
          <X class="h-3.5 w-3.5" />
        </button>
      </div>

      <button
        class="flex h-9 items-center gap-2 rounded-xl border border-surface-200 bg-white px-3 text-sm font-medium text-surface-700 shadow-card transition-all hover:bg-surface-50 hover:text-surface-900"
        @click="emit('createFolder')"
      >
        <FolderPlus class="h-4 w-4 text-primary-500" />
        新建文件夹
      </button>

      <!-- 上传按钮：Radix Vue DropdownMenu -->
      <DropdownMenuRoot>
        <DropdownMenuTrigger as-child>
          <button
            class="flex h-9 items-center gap-2 rounded-xl bg-primary-600 px-4 text-sm font-semibold text-white shadow-soft transition-all duration-200 hover:bg-primary-700 hover:shadow-card active:scale-95"
          >
            <Plus class="h-4 w-4" />
            上传
          </button>
        </DropdownMenuTrigger>

        <DropdownMenuContent
          align="end"
          :side-offset="8"
          class="min-w-[180px] overflow-hidden rounded-2xl border border-surface-200 bg-white p-1.5 shadow-soft outline-none"
        >
          <DropdownMenuItem
            class="flex cursor-pointer items-center gap-2.5 rounded-xl px-3 py-2 text-sm text-surface-700 outline-none transition-colors duration-150 hover:bg-primary-50 hover:text-primary-700 focus:bg-primary-50"
            @click="triggerFileSelect"
          >
            <FileUp class="h-4 w-4 text-primary-500" />
            上传文件
          </DropdownMenuItem>
          <DropdownMenuItem
            class="flex cursor-pointer items-center gap-2.5 rounded-xl px-3 py-2 text-sm text-surface-700 outline-none transition-colors duration-150 hover:bg-primary-50 hover:text-primary-700 focus:bg-primary-50"
            @click="emit('mockUpload')"
          >
            <FolderUp class="h-4 w-4 text-primary-500" />
            上传文件夹（占位）
          </DropdownMenuItem>
          <DropdownMenuSeparator class="my-1.5 h-px bg-surface-200" />
          <DropdownMenuItem
            class="flex cursor-pointer items-center gap-2.5 rounded-xl px-3 py-2 text-sm text-surface-700 outline-none transition-colors duration-150 hover:bg-primary-50 hover:text-primary-700 focus:bg-primary-50"
          >
            <Upload class="h-4 w-4 text-primary-500" />
            离线下载（占位）
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenuRoot>

      <input
        ref="fileInput"
        type="file"
        multiple
        class="hidden"
        @change="emit('fileChange', $event)"
      >
    </div>
  </div>
</template>
