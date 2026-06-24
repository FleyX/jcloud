<script setup lang="ts">
import { useConfirmStore } from '@/store/confirm'
import { AlertTriangle } from '@lucide/vue'
import {
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
  DialogTitle,
} from 'radix-vue'
import { cn } from '@/utils/cn'

const store = useConfirmStore()
</script>

<template>
  <DialogRoot v-model:open="store.visible">
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 z-50 bg-surface-900/40 backdrop-blur-sm" />
      <DialogContent
        class="fixed left-1/2 top-1/2 z-50 w-full max-w-sm -translate-x-1/2 -translate-y-1/2 rounded-2xl bg-white p-6 shadow-soft"
      >
        <div class="mb-4 flex items-center gap-3">
          <div
            :class="
              cn(
                'flex h-10 w-10 shrink-0 items-center justify-center rounded-full',
                store.type === 'danger' ? 'bg-red-100 text-red-600' : 'bg-primary-100 text-primary-600'
              )
            "
          >
            <AlertTriangle class="h-5 w-5" />
          </div>
          <div>
            <DialogTitle class="text-lg font-bold text-surface-900">
              {{ store.title }}
            </DialogTitle>
            <DialogDescription class="mt-1 text-sm text-surface-500">
              {{ store.message }}
            </DialogDescription>
          </div>
        </div>

        <div class="flex justify-end gap-3">
          <DialogClose as-child>
            <button
              class="rounded-xl border border-surface-200 bg-white px-4 py-2 text-sm font-medium text-surface-700 transition-colors hover:bg-surface-50"
              @click="store.cancel"
            >
              {{ store.cancelText }}
            </button>
          </DialogClose>
          <button
            :class="
              cn(
                'rounded-xl px-4 py-2 text-sm font-medium text-white shadow-soft transition-colors',
                store.type === 'danger' ? 'bg-red-600 hover:bg-red-700' : 'bg-primary-600 hover:bg-primary-700'
              )
            "
            @click="store.confirm"
          >
            {{ store.confirmText }}
          </button>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
