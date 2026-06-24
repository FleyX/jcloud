<script setup lang="ts">
import { computed } from 'vue'
import type { PermissionTreeVo } from '@/types/auth'

const props = defineProps<{
  tree: PermissionTreeVo[]
  checkedIds: string[]
}>()

const emit = defineEmits<{
  'update:checkedIds': [ids: string[]]
}>()

const localChecked = computed({
  get: () => [...props.checkedIds],
  set: (val) => emit('update:checkedIds', val),
})

function collectAncestorIds(tree: PermissionTreeVo[], targetId: string): string[] {
  function dfs(nodes: PermissionTreeVo[], parents: string[]): string[] | null {
    for (const node of nodes) {
      if (node.id === targetId) {
        return [...parents, node.id]
      }
      if (node.children) {
        const result = dfs(node.children, [...parents, node.id])
        if (result) return result
      }
    }
    return null
  }
  return dfs(tree, []) || []
}

function collectDescendantIds(node: PermissionTreeVo): string[] {
  const ids = [node.id]
  if (node.children) {
    node.children.forEach((child) => ids.push(...collectDescendantIds(child)))
  }
  return ids
}

function toggleNode(node: PermissionTreeVo, checked: boolean) {
  const current = new Set(localChecked.value)
  if (checked) {
    const ancestors = collectAncestorIds(props.tree, node.id)
    ancestors.forEach((id) => current.add(id))
  } else {
    const descendants = collectDescendantIds(node)
    descendants.forEach((id) => current.delete(id))
  }
  localChecked.value = Array.from(current)
}

function isChecked(id: string): boolean {
  return localChecked.value.includes(id)
}

function isIndeterminate(node: PermissionTreeVo): boolean {
  if (!node.children || node.children.length === 0) return false
  const descendants = collectDescendantIds(node).filter((id) => id !== node.id)
  const checkedDescendants = descendants.filter((id) => isChecked(id))
  return checkedDescendants.length > 0 && checkedDescendants.length < descendants.length
}
</script>

<template>
  <ul class="space-y-1">
    <li v-for="node in tree" :key="node.id" class="pl-4">
      <label class="flex items-center gap-2 py-1">
        <input
          type="checkbox"
          :checked="isChecked(node.id)"
          :indeterminate.prop="isIndeterminate(node)"
          class="h-4 w-4 rounded border-surface-300 text-primary-600 focus:ring-primary-500"
          @change="toggleNode(node, ($event.target as HTMLInputElement).checked)"
        />
        <span class="text-sm text-surface-700">{{ node.name }}</span>
        <span class="text-xs text-surface-400">({{ node.code }})</span>
      </label>
      <PermissionTree
        v-if="node.children && node.children.length > 0"
        :tree="node.children"
        v-model:checked-ids="localChecked"
      />
    </li>
  </ul>
</template>
