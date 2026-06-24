<script setup lang="ts">
import { useNotificationStore, type ToastMessage } from '@/store/notification'
import { X, CheckCircle2, AlertCircle, Info } from '@lucide/vue'
import { cn } from '@/utils/cn'

const store = useNotificationStore()

function iconForType(type: ToastMessage['type']) {
  switch (type) {
    case 'success':
      return CheckCircle2
    case 'error':
      return AlertCircle
    default:
      return Info
  }
}

function classesForType(type: ToastMessage['type']) {
  switch (type) {
    case 'success':
      return 'border-emerald-200 bg-emerald-50 text-emerald-800'
    case 'error':
      return 'border-red-200 bg-red-50 text-red-800'
    default:
      return 'border-primary-200 bg-white text-surface-800'
  }
}
</script>

<template>
  <div class="pointer-events-none fixed right-4 top-4 z-[100] flex flex-col gap-2">
    <transition-group
      enter-active-class="transition-all duration-300 ease-out-expo"
      enter-from-class="translate-x-full opacity-0"
      enter-to-class="translate-x-0 opacity-100"
      leave-active-class="transition-all duration-200 ease-in"
      leave-from-class="translate-x-0 opacity-100"
      leave-to-class="translate-x-full opacity-0"
    >
      <div
        v-for="toast in store.toasts"
        :key="toast.id"
        :class="cn('pointer-events-auto flex w-80 items-start gap-3 rounded-xl border p-4 shadow-soft', classesForType(toast.type))"
      >
        <component
          :is="iconForType(toast.type)"
          class="mt-0.5 h-5 w-5 shrink-0"
        />
        <p class="flex-1 text-sm font-medium leading-5">
          {{ toast.message }}
        </p>
        <button
          class="shrink-0 rounded-lg p-1 opacity-70 transition-opacity hover:opacity-100"
          @click="store.remove(toast.id)"
        >
          <X class="h-4 w-4" />
        </button>
      </div>
    </transition-group>
  </div>
</template>
