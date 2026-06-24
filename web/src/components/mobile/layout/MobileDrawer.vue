<script setup lang="ts">
import { X } from '@lucide/vue'
import {
  DialogClose,
  DialogContent,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
  DialogTitle,
} from 'radix-vue'
import { cn } from '@/utils/cn'
import type { SecondaryMenuItem } from '@/store/menu'

const props = defineProps<{
  open: boolean
  title: string
  menus: SecondaryMenuItem[]
  activeKey?: string
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  select: [item: SecondaryMenuItem]
}>()

function handleSelect(item: SecondaryMenuItem) {
  emit('select', item)
  emit('update:open', false)
}
</script>

<template>
  <DialogRoot
    :open="props.open"
    @update:open="emit('update:open', $event)"
  >
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 z-40 bg-surface-900/40" />
      <DialogContent
        class="fixed left-0 top-0 z-50 flex h-full w-64 flex-col bg-white shadow-card outline-none"
      >
        <div class="flex h-14 shrink-0 items-center justify-between border-b border-surface-200 px-4">
          <DialogTitle class="text-base font-bold text-surface-900">
            {{ title }}
          </DialogTitle>
          <DialogClose
            class="rounded-lg p-1 text-surface-400 outline-none hover:bg-surface-100 hover:text-surface-600"
          >
            <X class="h-5 w-5" />
          </DialogClose>
        </div>

        <nav class="flex-1 overflow-y-auto p-2">
          <button
            v-for="menu in menus"
            :key="menu.key"
            :class="
              cn(
                'flex w-full items-center rounded-xl px-3 py-3 text-sm font-medium transition-colors',
                activeKey === menu.key
                  ? 'bg-primary-50 text-primary-700'
                  : 'text-surface-700 hover:bg-surface-100'
              )
            "
            @click="handleSelect(menu)"
          >
            {{ menu.label }}
          </button>
        </nav>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
