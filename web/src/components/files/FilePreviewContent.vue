<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { FileText, Image as ImageIcon, Film, X, Download, Maximize, Minimize } from '@lucide/vue'
import { cn } from '@/utils/cn'
import { downloadFile } from '@/api/file'
import { resolvePreviewCategory } from '@/utils/previewCategory'
import PdfPreview from '@/components/files/PdfPreview.vue'
import type { FileNodeVo } from '@/types/file'

interface Props {
  file: FileNodeVo
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
}>()

const textContent = ref('')
const mediaUrl = ref('')
const officeData = ref<ArrayBuffer | null>(null)
const loading = ref(false)
const error = ref('')
const rootRef = ref<HTMLDivElement | null>(null)
const isFullscreen = ref(false)

function toggleFullscreen() {
  if (document.fullscreenElement) {
    void document.exitFullscreen()
  } else {
    void rootRef.value?.requestFullscreen()
  }
}

function syncFullscreenState() {
  isFullscreen.value = Boolean(document.fullscreenElement)
}

onMounted(() => {
  document.addEventListener('fullscreenchange', syncFullscreenState)
})

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', syncFullscreenState)
})

const category = computed(() => resolvePreviewCategory(props.file.mimeType, props.file.name))

async function fetchBlob(url: string): Promise<string> {
  const response = await fetch(url)
  if (!response.ok) throw new Error('预览加载失败')
  const blob = await response.blob()
  return URL.createObjectURL(blob)
}

async function extractError(response: Response, fallback: string): Promise<Error> {
  try {
    const data = await response.json() as { msg?: string }
    if (data.msg) return new Error(data.msg)
  } catch {
    // 非 JSON 响应，使用默认提示
  }
  return new Error(fallback)
}

async function loadPreview() {
  loading.value = true
  error.value = ''
  textContent.value = ''
  officeData.value = null
  if (mediaUrl.value) {
    URL.revokeObjectURL(mediaUrl.value)
    mediaUrl.value = ''
  }

  try {
    if (category.value === 'image') {
      mediaUrl.value = await fetchBlob(`/jcloud/api/files/${props.file.id}/preview?type=thumbnail`)
    } else if (category.value === 'office') {
      const response = await fetch(`/jcloud/api/files/${props.file.id}/preview?type=office`)
      if (!response.ok) throw await extractError(response, '预览加载失败')
      officeData.value = await response.arrayBuffer()
    } else if (category.value === 'text') {
      const response = await fetch(`/jcloud/api/files/${props.file.id}/preview?type=text`)
      if (!response.ok) throw new Error('文本加载失败')
      const data = await response.json() as { content?: string }
      textContent.value = data.content || ''
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '预览加载失败'
  } finally {
    loading.value = false
  }
}

watch(() => props.file.id, () => {
  void loadPreview()
}, { immediate: true })

function handleDownload() {
  downloadFile(props.file.id)
}
</script>

<template>
  <div
    ref="rootRef"
    class="flex h-full flex-col bg-surface-900"
  >
    <!-- 顶部栏 -->
    <div class="flex items-center justify-between px-4 py-3 text-white">
      <div class="flex min-w-0 items-center gap-3">
        <div
          :class="
            cn(
              'flex h-8 w-8 shrink-0 items-center justify-center rounded-lg',
              category === 'image' && 'bg-purple-500/20 text-purple-300',
              category === 'video' && 'bg-rose-500/20 text-rose-300',
              category === 'text' && 'bg-blue-500/20 text-blue-300',
              category === 'office' && 'bg-amber-500/20 text-amber-300',
              category === 'unsupported' && 'bg-surface-500/20 text-surface-300'
            )
          "
        >
          <ImageIcon
            v-if="category === 'image'"
            class="h-4 w-4"
          />
          <Film
            v-else-if="category === 'video'"
            class="h-4 w-4"
          />
          <FileText
            v-else
            class="h-4 w-4"
          />
        </div>
        <span class="truncate text-sm font-medium">{{ file.name }}</span>
      </div>
      <div class="flex items-center gap-2">
        <button
          class="rounded-lg p-2 text-surface-300 transition-colors hover:bg-white/10 hover:text-white"
          :title="isFullscreen ? '退出全屏' : '全屏显示'"
          @click="toggleFullscreen"
        >
          <Minimize
            v-if="isFullscreen"
            class="h-5 w-5"
          />
          <Maximize
            v-else
            class="h-5 w-5"
          />
        </button>
        <button
          class="rounded-lg p-2 text-surface-300 transition-colors hover:bg-white/10 hover:text-white"
          @click="handleDownload"
        >
          <Download class="h-5 w-5" />
        </button>
        <button
          class="rounded-lg p-2 text-surface-300 transition-colors hover:bg-white/10 hover:text-white"
          @click="emit('close')"
        >
          <X class="h-5 w-5" />
        </button>
      </div>
    </div>

    <!-- 内容区 -->
    <div class="flex flex-1 items-center justify-center overflow-hidden p-4">
      <div
        v-if="loading"
        class="text-center text-surface-400"
      >
        加载中...
      </div>
      <img
        v-else-if="category === 'image'"
        :src="mediaUrl"
        :alt="file.name"
        class="max-h-full max-w-full rounded-lg object-contain shadow-lg"
      >
      <div
        v-else-if="category === 'office'"
        class="flex h-full w-full items-center justify-center"
      >
        <div
          v-if="error"
          class="text-center"
        >
          <p class="text-sm text-red-400">
            {{ error }}
          </p>
          <button
            class="mt-4 rounded-xl bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700"
            @click="handleDownload"
          >
            下载文件
          </button>
        </div>
        <PdfPreview
          v-else-if="officeData"
          :data="officeData"
        />
      </div>
      <div
        v-else-if="category === 'text'"
        class="h-full w-full max-w-4xl overflow-auto rounded-lg bg-surface-800 p-6 text-sm text-surface-200"
      >
        <div
          v-if="error"
          class="text-center text-red-400"
        >
          {{ error }}
        </div>
        <pre
          v-else
          class="whitespace-pre-wrap font-mono"
        >{{ textContent }}</pre>
      </div>
      <div
        v-else
        class="text-center text-surface-300"
      >
        <FileText class="mx-auto mb-3 h-12 w-12 text-surface-500" />
        <p class="text-sm">
          暂不支持该文件类型的预览
        </p>
        <button
          class="mt-4 rounded-xl bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700"
          @click="handleDownload"
        >
          下载文件
        </button>
      </div>
    </div>
  </div>
</template>
