<script setup lang="ts">
import {
  DialogContent,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
} from 'radix-vue'
import FilePreviewContent from './FilePreviewContent.vue'
import type { FileNodeVo } from '@/types/file'

interface Props {
  open: boolean
  file: FileNodeVo | null
}

defineProps<Props>()
const emit = defineEmits<{
  'update:open': [value: boolean]
}>()

function handleClose() {
  emit('update:open', false)
}
</script>

<template>
  <DialogRoot
    :open="open"
    @update:open="emit('update:open', $event)"
  >
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 z-50 bg-surface-900/80 backdrop-blur-sm" />
      <DialogContent
        class="fixed left-1/2 top-1/2 z-50 h-[85vh] w-[90vw] max-w-5xl -translate-x-1/2 -translate-y-1/2 overflow-hidden rounded-2xl shadow-2xl outline-none"
      >
        <FilePreviewContent
          v-if="file"
          :file="file"
          @close="handleClose"
        />
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
