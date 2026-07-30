<script setup lang="ts">
/**
 * 通用多选目录选择弹窗（由 FolderTree 抽取的多选版本）
 * - 懒加载文件夹树，复选勾选
 * - 底部展示本次已选列表，可单个移除
 */
import { computed, ref, watch } from 'vue'
import { X, Folder, FolderOpen, ChevronRight, ChevronDown, Check } from '@lucide/vue'
import { fetchChildFolders } from '@/api/file'
import { cn } from '@/utils/cn'

export interface PickedDirectory {
  id: string
  name: string
}

interface TreeNode {
  id: string
  name: string
  children: TreeNode[]
  loaded: boolean
  expanded: boolean
  loading: boolean
}

interface FlatNode {
  node: TreeNode
  depth: number
}

interface Props {
  open: boolean
  /** 已占用（不可重复选择）的目录 ID，如表单中已有来源目录 */
  disabledIds?: string[]
}

const props = withDefaults(defineProps<Props>(), {
  disabledIds: () => [],
})

const emit = defineEmits<{
  close: []
  confirm: [nodes: PickedDirectory[]]
}>()

const root = ref<TreeNode>({
  id: '0',
  name: '根目录',
  children: [],
  loaded: false,
  expanded: true,
  loading: false,
})

/** 本次已勾选：id -> name */
const checked = ref<Record<string, string>>({})

watch(
  () => props.open,
  (open) => {
    if (!open) return
    checked.value = {}
    root.value = { id: '0', name: '根目录', children: [], loaded: false, expanded: true, loading: false }
    loadChildren(root.value)
  },
)

async function loadChildren(node: TreeNode) {
  if (node.loading || node.loaded) return
  node.loading = true
  try {
    const folders = await fetchChildFolders(node.id)
    node.children = folders.map((folder) => ({
      id: folder.id,
      name: folder.name,
      children: [],
      loaded: false,
      expanded: false,
      loading: false,
    }))
    node.loaded = true
  } finally {
    node.loading = false
  }
}

async function toggleExpand(node: TreeNode) {
  if (!node.expanded) {
    await loadChildren(node)
  }
  node.expanded = !node.expanded
}

function isDisabled(id: string): boolean {
  return props.disabledIds.includes(id)
}

function toggleCheck(node: TreeNode) {
  if (isDisabled(node.id)) return
  const next = { ...checked.value }
  if (next[node.id]) {
    delete next[node.id]
  } else {
    next[node.id] = node.name
  }
  checked.value = next
}

function uncheck(id: string) {
  const next = { ...checked.value }
  delete next[id]
  checked.value = next
}

/** 是否显示展开箭头：未加载的节点无法判断是否有子文件夹，一律显示 */
function showToggle(node: TreeNode): boolean {
  return !node.loaded || node.children.length > 0
}

/** 将展开的树拍平为渲染列表（避免递归组件） */
const flatNodes = computed<FlatNode[]>(() => {
  const out: FlatNode[] = []
  const walk = (node: TreeNode, depth: number) => {
    for (const child of node.children) {
      out.push({ node: child, depth })
      if (child.expanded) walk(child, depth + 1)
    }
  }
  walk(root.value, 0)
  return out
})

const checkedList = computed<PickedDirectory[]>(() =>
  Object.entries(checked.value).map(([id, name]) => ({ id, name })),
)

function handleConfirm() {
  emit('confirm', checkedList.value)
  emit('close')
}
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
    @click.self="emit('close')"
  >
    <div class="flex max-h-[85vh] w-full max-w-md flex-col rounded-3xl border border-surface-200 bg-white p-6 shadow-soft">
      <div class="mb-4 flex items-center gap-3">
        <h3 class="text-lg font-semibold text-surface-900">
          选择目录
        </h3>
        <button
          class="ml-auto rounded-lg p-1 text-surface-400 hover:bg-surface-100"
          @click="emit('close')"
        >
          <X class="h-4 w-4" />
        </button>
      </div>

      <div class="min-h-0 flex-1 overflow-y-auto rounded-2xl border border-surface-200 bg-surface-50/50 p-1">
        <p
          v-if="flatNodes.length === 0"
          class="px-3 py-4 text-center text-sm text-surface-500"
        >
          {{ root.loading ? '加载中...' : '无子文件夹' }}
        </p>
        <ul
          v-else
          class="space-y-0.5"
        >
          <li
            v-for="{ node, depth } in flatNodes"
            :key="node.id"
          >
            <div
              :class="
                cn(
                  'flex items-center gap-1 rounded-xl px-2 py-2 text-sm transition-colors',
                  isDisabled(node.id)
                    ? 'cursor-not-allowed text-surface-400'
                    : 'cursor-pointer text-surface-700 hover:bg-surface-100'
                )
              "
              :style="{ paddingLeft: `${8 + depth * 16}px` }"
              @click="toggleCheck(node)"
            >
              <button
                :class="
                  cn(
                    'flex h-5 w-5 shrink-0 items-center justify-center rounded transition-colors',
                    showToggle(node) ? 'text-surface-500 hover:bg-surface-200' : 'pointer-events-none opacity-0'
                  )
                "
                @click.stop="toggleExpand(node)"
              >
                <ChevronDown
                  v-if="node.expanded"
                  class="h-3.5 w-3.5"
                />
                <ChevronRight
                  v-else
                  class="h-3.5 w-3.5"
                />
              </button>
              <FolderOpen
                v-if="node.expanded"
                class="h-4 w-4 shrink-0 text-amber-500"
              />
              <Folder
                v-else
                class="h-4 w-4 shrink-0 text-amber-500"
              />
              <span class="truncate">{{ node.name }}</span>
              <span
                v-if="node.loading"
                class="ml-auto text-xs text-surface-400"
              >加载中...</span>
              <span
                v-else
                :class="
                  cn(
                    'ml-auto flex h-4 w-4 shrink-0 items-center justify-center rounded border',
                    checked[node.id]
                      ? 'border-primary-600 bg-primary-600 text-white'
                      : isDisabled(node.id)
                        ? 'border-surface-200 bg-surface-100'
                        : 'border-surface-300 bg-white'
                  )
                "
              >
                <Check
                  v-if="checked[node.id]"
                  class="h-3 w-3"
                />
              </span>
            </div>
          </li>
        </ul>
      </div>

      <!-- 本次已选 -->
      <div
        v-if="checkedList.length > 0"
        class="mt-3 flex flex-wrap gap-1.5"
      >
        <span
          v-for="item in checkedList"
          :key="item.id"
          class="flex items-center gap-1 rounded-lg bg-primary-50 px-2 py-1 text-xs text-primary-700"
        >
          {{ item.name }}
          <button
            class="rounded hover:text-primary-900"
            @click="uncheck(item.id)"
          >
            <X class="h-3 w-3" />
          </button>
        </span>
      </div>

      <div class="mt-4 flex justify-end gap-2">
        <button
          class="rounded-xl px-4 py-2 text-sm font-medium text-surface-600 hover:bg-surface-100"
          @click="emit('close')"
        >
          取消
        </button>
        <button
          :class="
            cn(
              'rounded-xl px-4 py-2 text-sm font-semibold text-white',
              checkedList.length > 0 ? 'bg-primary-600 hover:bg-primary-700' : 'bg-surface-300 cursor-not-allowed'
            )
          "
          :disabled="checkedList.length === 0"
          @click="handleConfirm"
        >
          添加所选（{{ checkedList.length }}）
        </button>
      </div>
    </div>
  </div>
</template>
