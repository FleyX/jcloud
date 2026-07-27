<script setup lang="ts">
/**
 * 影视设置（PC 端，管理员）
 */
import { Clapperboard } from '@lucide/vue'
import TmdbConfigCard from '@/components/media/TmdbConfigCard.vue'
import TranscodeConfigCard from '@/components/media/TranscodeConfigCard.vue'
</script>

<template>
  <div>
    <div class="mb-6 flex items-center gap-3">
      <div class="rounded-xl bg-primary-50 p-2.5 text-primary-600">
        <Clapperboard class="h-5 w-5" />
      </div>
      <div>
        <h2 class="text-lg font-bold text-surface-900">
          影视设置
        </h2>
        <p class="text-xs text-surface-500">
          影视媒体库的元数据来源与转码参数
        </p>
      </div>
    </div>

    <div class="space-y-6">
      <TmdbConfigCard />
      <TranscodeConfigCard />
    </div>
  </div>
</template>
