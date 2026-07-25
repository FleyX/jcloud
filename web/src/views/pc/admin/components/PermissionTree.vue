<script setup lang="ts">
import { computed } from 'vue'
import type { PermissionTreeVo } from '@/types/auth'

const props = defineProps<{
  tree: PermissionTreeVo[]
  checkedCodes: string[]
}>()

const emit = defineEmits<{
  'update:checkedCodes': [codes: string[]]
}>()

const localChecked = computed({
  get: () => [...props.checkedCodes],
  set: (val) => emit('update:checkedCodes', val),
})

function collectAncestorCodes(tree: PermissionTreeVo[], targetCode: string): string[] {
  function dfs(nodes: PermissionTreeVo[], parents: string[]): string[] | null {
    for (const node of nodes) {
      if (node.code === targetCode) {
        return [...parents, node.code]
      }
      if (node.children) {
        const result = dfs(node.children, [...parents, node.code])
        if (result) return result
      }
    }
    return null
  }
  return dfs(tree, []) || []
}

function collectDescendantCodes(node: PermissionTreeVo): string[] {
  const ids = [node.code]
  if (node.children) {
    node.children.forEach((child) => ids.push(...collectDescendantCodes(child)))
  }
  return ids
}

function toggleNode(node: PermissionTreeVo, checked: boolean) {
  const current = new Set(localChecked.value)
  if (checked) {
    const ancestors = collectAncestorCodes(props.tree, node.code)
    const descendants = collectDescendantCodes(node)
    ;[...ancestors, ...descendants].forEach((code) => current.add(code))
  } else {
    const descendants = collectDescendantCodes(node)
    descendants.forEach((code) => current.delete(code))
  }
  localChecked.value = Array.from(current)
}

function isChecked(code: string): boolean {
  return localChecked.value.includes(code)
}

function isIndeterminate(node: PermissionTreeVo): boolean {
  if (!node.children || node.children.length === 0) return false
  const descendants = collectDescendantCodes(node).filter((code) => code !== node.code)
  const checkedDescendants = descendants.filter((code) => isChecked(code))
  return checkedDescendants.length > 0 && checkedDescendants.length < descendants.length
}
</script>

<template>
  <ul class="space-y-1">
    <li
      v-for="node in tree"
      :key="node.code"
      class="pl-4"
    >
      <label class="flex items-center gap-2 py-1">
        <input
          type="checkbox"
          :checked="isChecked(node.code)"
          :indeterminate.prop="isIndeterminate(node)"
          class="h-4 w-4 rounded border-surface-300 text-primary-600 focus:ring-primary-500"
          @change="toggleNode(node, ($event.target as HTMLInputElement).checked)"
        >
        <span class="text-sm text-surface-700">{{ node.name }}</span>
        <span class="text-xs text-surface-400">({{ node.code }})</span>
      </label>
      <PermissionTree
        v-if="node.children && node.children.length > 0"
        v-model:checked-codes="localChecked"
        :tree="node.children"
      />
    </li>
  </ul>
</template>
