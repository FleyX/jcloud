<script setup lang="ts">
/**
 * 文件夹树选择器
 * - 默认展示根目录和根目录下的一级文件夹
 * - 点击展开节点时懒加载该节点的直接子文件夹
 * - 支持本地搜索已加载节点
 */
import { computed, onMounted, ref } from 'vue'
import { fetchChildFolders } from '@/api/file'
import TreeNodeItem from './TreeNodeItem.vue'

export interface TreeNode {
  id: string
  name: string
  sourceType?: 'local' | 'remote'
  remoteMountId?: string
  children: TreeNode[]
  loaded: boolean
  expanded: boolean
  loading: boolean
}

interface Props {
  selectedId: string
  disabledIds?: string[]
  keyword?: string
}

const props = withDefaults(defineProps<Props>(), {
  disabledIds: () => [],
  keyword: '',
})

const emit = defineEmits<{
  select: [node: TreeNode]
}>()

const root = ref<TreeNode>({
  id: '0',
  name: '根目录',
  children: [],
  loaded: false,
  expanded: true,
  loading: false,
})

const loadingRoot = ref(false)

async function loadChildren(node: TreeNode) {
  if (node.loading || node.loaded) return
  node.loading = true
  try {
    const folders = await fetchChildFolders(node.id)
    node.children = folders.map((folder) => ({
      id: folder.id,
      name: folder.name,
      sourceType: folder.sourceType,
      remoteMountId: folder.remoteMountId,
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

function handleSelect(node: TreeNode) {
  if (props.disabledIds.includes(node.id)) return
  emit('select', node)
}

function isMatch(node: TreeNode): boolean {
  const keyword = props.keyword.trim().toLowerCase()
  if (!keyword) return true
  return node.name.toLowerCase().includes(keyword)
}

function filterTree(node: TreeNode): TreeNode | null {
  const keyword = props.keyword.trim().toLowerCase()
  if (!keyword) return node

  const matched = isMatch(node)
  const filteredChildren = node.children
    .map(filterTree)
    .filter((child): child is TreeNode => child !== null)

  if (matched || filteredChildren.length > 0) {
    return {
      ...node,
      children: filteredChildren,
      expanded: true,
    }
  }
  return null
}

const displayRoot = computed(() => filterTree(root.value))

onMounted(async () => {
  loadingRoot.value = true
  try {
    await loadChildren(root.value)
  } finally {
    loadingRoot.value = false
  }
})
</script>

<template>
  <div class="rounded-2xl border border-surface-200 bg-surface-50/50 p-1">
    <div
      v-if="loadingRoot"
      class="px-3 py-4 text-center text-sm text-surface-500"
    >
      加载中...
    </div>
    <ul
      v-else
      class="space-y-0.5"
    >
      <TreeNodeItem
        v-if="displayRoot"
        :node="displayRoot"
        :selected-id="selectedId"
        :disabled-ids="disabledIds"
        @toggle="toggleExpand"
        @select="handleSelect"
      />
    </ul>
  </div>
</template>
