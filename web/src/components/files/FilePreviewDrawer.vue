<script setup lang="ts">
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
  <Transition
    enter-active-class="transition-opacity duration-200"
    enter-from-class="opacity-0"
    enter-to-class="opacity-100"
    leave-active-class="transition-opacity duration-200"
    leave-from-class="opacity-100"
    leave-to-class="opacity-0"
  >
    <div
      v-if="open"
      class="fixed inset-0 z-50"
      @click.self="handleClose"
    >
      <div class="absolute inset-0 bg-surface-900/90 backdrop-blur-sm" />
      <div class="absolute inset-0 overflow-hidden">
        <FilePreviewContent
          v-if="file"
          :file="file"
          @close="handleClose"
        />
      </div>
    </div>
  </Transition>
</template>
