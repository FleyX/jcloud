<script setup lang="ts">
/**
 * 文件夹树节点项（递归）
 */
import { ChevronRight, ChevronDown, Folder, FolderOpen } from '@lucide/vue'
import { cn } from '@/utils/cn'

interface TreeNode {
  id: string
  name: string
  children: TreeNode[]
  loaded: boolean
  expanded: boolean
  loading: boolean
}

interface Props {
  node: TreeNode
  selectedId: string
  disabledIds?: string[]
  depth?: number
}

const props = withDefaults(defineProps<Props>(), {
  disabledIds: () => [],
  depth: 0,
})

const emit = defineEmits<{
  toggle: [node: TreeNode]
  select: [node: TreeNode]
}>()

function isDisabled(id: string): boolean {
  return props.disabledIds.includes(id)
}

function hasChildren(node: TreeNode): boolean {
  return node.children.length > 0
}
</script>

<template>
  <li>
    <div
      :class="cn(
        'flex items-center gap-1 rounded-xl px-2 py-2 text-sm transition-colors',
        isDisabled(node.id)
          ? 'cursor-not-allowed text-surface-400'
          : 'cursor-pointer text-surface-700 hover:bg-surface-100',
        selectedId === node.id && !isDisabled(node.id) && 'bg-primary-50 text-primary-700'
      )"
      :style="{ paddingLeft: `${12 + depth * 16}px` }"
      @click="emit('select', node)"
    >
      <button
        :class="cn(
          'flex h-5 w-5 shrink-0 items-center justify-center rounded transition-colors',
          hasChildren(node) ? 'text-surface-500 hover:bg-surface-200' : 'pointer-events-none opacity-0'
        )"
        @click.stop="emit('toggle', node)"
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
        v-if="node.expanded && node.id !== '0'"
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
    </div>
    <ul
      v-if="node.expanded && node.children.length > 0"
      class="space-y-0.5"
    >
      <TreeNodeItem
        v-for="child in node.children"
        :key="child.id"
        :node="child"
        :selected-id="selectedId"
        :disabled-ids="disabledIds"
        :depth="depth + 1"
        @toggle="emit('toggle', $event)"
        @select="emit('select', $event)"
      />
    </ul>
  </li>
</template>
