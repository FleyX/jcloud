<script setup lang="ts">
/**
 * 影视设置（移动端，管理员）
 */
import TmdbConfigCard from '@/components/media/TmdbConfigCard.vue'
import TranscodeConfigCard from '@/components/media/TranscodeConfigCard.vue'
</script>

<template>
  <div class="space-y-4 p-4">
    <TmdbConfigCard />
    <TranscodeConfigCard />
  </div>
</template>
