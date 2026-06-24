<script setup lang="ts">
/**
 * 主文件列表页面
 * - 顶部面包屑 + 上传按钮（Radix Vue DropdownMenu 占位）
 * - 文件列表表格：表头 + Mock 数据行
 * - 支持行悬浮、选中状态样式切换，配合过渡动画
 */
import { ref } from 'vue'
import {
  DropdownMenuRoot,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
} from 'radix-vue'
import {
  ChevronRight,
  Upload,
  FileUp,
  FolderUp,
  Plus,
  MoreHorizontal,
  FileText,
  Image as ImageIcon,
  Film,
  Music,
  Search,
  LayoutGrid,
  List,
} from '@lucide/vue'
import { cn } from '@/utils/cn'
import { useTransferStore } from '@/store/transfer'
import type { Component } from 'vue'

const transferStore = useTransferStore()

// 面包屑数据（静态展示，当前未实现文件夹导航）
const breadcrumbs = ['全部文件', '工作文档', '设计素材']

// 文件列表 Mock 数据
interface FileItem {
  id: string
  name: string
  type: 'image' | 'video' | 'audio' | 'doc' | 'folder'
  size: string
  updatedAt: string
  selected?: boolean
}

const files = ref<FileItem[]>([
  {
    id: '1',
    name: '2024-Q4-产品规划.pdf',
    type: 'doc',
    size: '2.4 MB',
    updatedAt: '2024-11-20',
    selected: false,
  },
  {
    id: '2',
    name: '首页 Banner 设计稿.png',
    type: 'image',
    size: '8.7 MB',
    updatedAt: '2024-11-18',
    selected: true,
  },
  {
    id: '3',
    name: '产品发布会开场视频.mp4',
    type: 'video',
    size: '156 MB',
    updatedAt: '2024-11-15',
    selected: false,
  },
  {
    id: '4',
    name: '背景音乐精选.mp3',
    type: 'audio',
    size: '12.1 MB',
    updatedAt: '2024-11-12',
    selected: false,
  },
])

// 根据文件类型返回对应图标组件
const fileIconMap: Record<FileItem['type'], Component> = {
  doc: FileText,
  image: ImageIcon,
  video: Film,
  audio: Music,
  folder: FolderUp,
}

// 切换行选中状态
function toggleSelect(file: FileItem) {
  file.selected = !file.selected
}

// 模拟添加上传任务
function handleMockUpload() {
  transferStore.addUploadTask({
    fileId: `mock-${Date.now()}`,
    fileName: '示例上传文件.zip',
  })
}
</script>

<template>
  <div class="mx-auto h-full max-w-7xl">
    <!-- 顶部工具栏 -->
    <div class="mb-6 flex items-center justify-between">
      <!-- 面包屑导航 -->
      <nav class="flex items-center gap-1 text-sm">
        <span
          v-for="(crumb, index) in breadcrumbs"
          :key="index"
          class="flex items-center gap-1"
        >
          <span
            :class="
              cn(
                'font-medium',
                index === breadcrumbs.length - 1 ? 'font-semibold text-surface-900' : 'text-surface-500'
              )
            "
          >
            {{ crumb }}
          </span>
          <ChevronRight
            v-if="index < breadcrumbs.length - 1"
            class="h-4 w-4 text-surface-300"
          />
        </span>
      </nav>

      <div class="flex items-center gap-3">
        <!-- 视图切换 -->
        <div class="flex items-center rounded-xl border border-surface-200 bg-white p-1 shadow-card">
          <button class="rounded-lg p-1.5 text-surface-400 hover:bg-surface-100 hover:text-surface-700">
            <LayoutGrid class="h-4 w-4" />
          </button>
          <button class="rounded-lg bg-surface-100 p-1.5 text-surface-700">
            <List class="h-4 w-4" />
          </button>
        </div>

        <!-- 搜索框 -->
        <div
          class="flex h-9 items-center gap-2 rounded-xl border border-surface-200 bg-white px-3 shadow-card transition-shadow duration-200 focus-within:border-primary-300 focus-within:ring-2 focus-within:ring-primary-100"
        >
          <Search class="h-4 w-4 text-surface-400" />
          <input
            type="text"
            placeholder="搜索文件..."
            class="w-48 bg-transparent text-sm outline-none placeholder:text-surface-400"
          >
        </div>

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
              @click="handleMockUpload"
            >
              <FileUp class="h-4 w-4 text-primary-500" />
              上传文件
            </DropdownMenuItem>
            <DropdownMenuItem
              class="flex cursor-pointer items-center gap-2.5 rounded-xl px-3 py-2 text-sm text-surface-700 outline-none transition-colors duration-150 hover:bg-primary-50 hover:text-primary-700 focus:bg-primary-50"
            >
              <FolderUp class="h-4 w-4 text-primary-500" />
              上传文件夹
            </DropdownMenuItem>
            <DropdownMenuSeparator class="my-1.5 h-px bg-surface-200" />
            <DropdownMenuItem
              class="flex cursor-pointer items-center gap-2.5 rounded-xl px-3 py-2 text-sm text-surface-700 outline-none transition-colors duration-150 hover:bg-primary-50 hover:text-primary-700 focus:bg-primary-50"
            >
              <Upload class="h-4 w-4 text-primary-500" />
              离线下载
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenuRoot>
      </div>
    </div>

    <!-- 文件列表表格 -->
    <div
      class="overflow-hidden rounded-3xl border border-surface-200 bg-white shadow-soft"
    >
      <!-- 表头 -->
      <div
        class="grid grid-cols-[48px_1fr_140px_160px_80px] items-center border-b border-surface-200 bg-surface-50/80 px-5 py-3 text-xs font-semibold uppercase tracking-wider text-surface-500"
      >
        <div>
          <div
            class="h-4 w-4 rounded border border-surface-300 bg-white transition-colors duration-150 hover:border-primary-400"
          />
        </div>
        <span>文件名</span>
        <span>大小</span>
        <span>修改时间</span>
        <span class="text-right">操作</span>
      </div>

      <!-- 文件行 -->
      <div class="divide-y divide-surface-100">
        <div
          v-for="file in files"
          :key="file.id"
          :class="
            cn(
              'group grid cursor-pointer grid-cols-[48px_1fr_140px_160px_80px] items-center px-5 py-3.5 text-sm transition-all duration-200 ease-out-expo',
              file.selected
                ? 'bg-primary-50/60 hover:bg-primary-50'
                : 'hover:bg-surface-50'
            )
          "
          @click="toggleSelect(file)"
        >
          <!-- 复选框 -->
          <div class="flex items-center">
            <div
              :class="
                cn(
                  'flex h-4 w-4 items-center justify-center rounded border transition-all duration-200',
                  file.selected
                    ? 'border-primary-500 bg-primary-500'
                    : 'border-surface-300 bg-white group-hover:border-primary-400'
                )
              "
            >
              <transition
                enter-active-class="transition-transform duration-200 ease-out-expo"
                enter-from-class="scale-0"
                enter-to-class="scale-100"
                leave-active-class="transition-transform duration-150 ease-in"
                leave-from-class="scale-100"
                leave-to-class="scale-0"
              >
                <svg
                  v-if="file.selected"
                  class="h-3 w-3 text-white"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                  stroke-width="3"
                >
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    d="M5 13l4 4L19 7"
                  />
                </svg>
              </transition>
            </div>
          </div>

          <!-- 文件名 -->
          <div class="flex min-w-0 items-center gap-3">
            <div
              :class="
                cn(
                  'flex h-10 w-10 shrink-0 items-center justify-center rounded-xl transition-colors duration-200',
                  file.type === 'image' && 'bg-purple-100 text-purple-600',
                  file.type === 'video' && 'bg-rose-100 text-rose-600',
                  file.type === 'audio' && 'bg-amber-100 text-amber-600',
                  file.type === 'doc' && 'bg-blue-100 text-blue-600',
                  file.type === 'folder' && 'bg-emerald-100 text-emerald-600'
                )
              "
            >
              <component
                :is="fileIconMap[file.type]"
                class="h-5 w-5"
              />
            </div>
            <span
              :class="
                cn(
                  'line-clamp-1 font-medium transition-colors duration-200',
                  file.selected ? 'text-primary-700' : 'text-surface-800'
                )
              "
            >
              {{ file.name }}
            </span>
          </div>

          <!-- 大小 -->
          <span class="text-surface-500">{{ file.size }}</span>

          <!-- 修改时间 -->
          <span class="text-surface-500">{{ file.updatedAt }}</span>

          <!-- 操作按钮 -->
          <div class="flex justify-end">
            <button
              class="rounded-lg p-1.5 text-surface-400 opacity-0 transition-all duration-200 hover:bg-surface-100 hover:text-surface-700 group-hover:opacity-100"
              @click.stop
            >
              <MoreHorizontal class="h-4 w-4" />
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
