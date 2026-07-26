<script setup lang="ts">
import { onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { ZoomIn, ZoomOut } from '@lucide/vue'
import * as pdfjsLib from 'pdfjs-dist'
import type { PDFDocumentProxy } from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'

pdfjsLib.GlobalWorkerOptions.workerSrc = workerUrl

interface Props {
  /** PDF 二进制数据 */
  data: ArrayBuffer
}

const props = defineProps<Props>()

const loading = ref(true)
const error = ref('')
const scale = ref(1)
const numPages = ref(0)
const currentPage = ref(1)
const pageInput = ref('1')
const scrollRef = ref<HTMLDivElement | null>(null)
const pageEls = ref<(HTMLDivElement | null)[]>([])

const doc = shallowRef<PDFDocumentProxy | null>(null)
let loadingTask: ReturnType<typeof pdfjsLib.getDocument> | null = null
const renderedPages = new Set<number>()
const renderingPages = new Set<number>()
let observer: IntersectionObserver | null = null
let renderSeq = 0

function setPageEl(index: number) {
  return (el: Element | import('vue').ComponentPublicInstance | null) => {
    pageEls.value[index] = (el as HTMLDivElement | null) ?? null
    const div = pageEls.value[index]
    if (div && observer) {
      observer.observe(div)
    }
  }
}

async function renderPage(n: number) {
  const pdf = doc.value
  const el = pageEls.value[n - 1]
  if (!pdf || !el || renderingPages.has(n)) return
  renderingPages.add(n)
  const seq = renderSeq
  try {
    const page = await pdf.getPage(n)
    if (seq !== renderSeq) return
    const cssViewport = page.getViewport({ scale: scale.value })
    const dpr = window.devicePixelRatio || 1
    el.style.minHeight = `${cssViewport.height}px`
    let canvas = el.querySelector('canvas')
    if (!canvas) {
      canvas = document.createElement('canvas')
      canvas.className = 'rounded-lg shadow-lg'
      el.appendChild(canvas)
    }
    canvas.width = Math.floor(cssViewport.width * dpr)
    canvas.height = Math.floor(cssViewport.height * dpr)
    canvas.style.width = `${cssViewport.width}px`
    canvas.style.height = `${cssViewport.height}px`
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    await page.render({
      canvasContext: ctx,
      canvas,
      viewport: page.getViewport({ scale: scale.value * dpr }),
    }).promise
    if (seq !== renderSeq) return
    renderedPages.add(n)
  } finally {
    renderingPages.delete(n)
  }
}

function setupObserver() {
  observer?.disconnect()
  observer = new IntersectionObserver(
    (entries) => {
      const visible: number[] = []
      for (const entry of entries) {
        const n = Number((entry.target as HTMLDivElement).dataset.page)
        if (!entry.isIntersecting) continue
        visible.push(n)
        if (!renderedPages.has(n)) {
          void renderPage(n)
        }
      }
      if (visible.length > 0) {
        currentPage.value = Math.min(...visible)
        pageInput.value = String(currentPage.value)
      }
    },
    { root: scrollRef.value, rootMargin: '200px 0px' }
  )
  for (const el of pageEls.value) {
    if (el) observer.observe(el)
  }
}

const estimatedRatio = ref(1.3)

async function load() {
  loading.value = true
  error.value = ''
  numPages.value = 0
  renderedPages.clear()
  renderingPages.clear()
  renderSeq++
  loadingTask?.destroy()
  loadingTask = null
  doc.value = null
  try {
    loadingTask = pdfjsLib.getDocument({ data: props.data })
    const pdf = await loadingTask.promise
    doc.value = pdf
    numPages.value = pdf.numPages
    pageEls.value = new Array(pdf.numPages).fill(null)
    // 用第 1 页尺寸预估占位高度，减少滚动抖动
    const firstPage = await pdf.getPage(1)
    const v = firstPage.getViewport({ scale: scale.value })
    estimatedRatio.value = v.height / v.width
    loading.value = false
    // 等待 DOM 更新后再建立观察器
    requestAnimationFrame(() => setupObserver())
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'PDF 解析失败'
    loading.value = false
  }
}

function reRenderAll() {
  renderSeq++
  const pages = [...renderedPages]
  renderedPages.clear()
  for (const n of pages) {
    const el = pageEls.value[n - 1]
    el?.querySelector('canvas')?.remove()
  }
  for (const n of pages) {
    void renderPage(n)
  }
}

function zoom(delta: number) {
  const next = Math.min(3, Math.max(0.4, scale.value + delta))
  if (next === scale.value) return
  scale.value = next
  reRenderAll()
}

function jumpToPage() {
  const n = Number(pageInput.value)
  if (!Number.isInteger(n) || n < 1 || n > numPages.value) {
    pageInput.value = String(currentPage.value)
    return
  }
  pageEls.value[n - 1]?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  currentPage.value = n
}

watch(() => props.data, () => {
  void load()
}, { immediate: true })

onBeforeUnmount(() => {
  observer?.disconnect()
  loadingTask?.destroy()
})
</script>

<template>
  <div class="relative flex h-full w-full flex-col overflow-hidden">
    <div
      v-if="loading"
      class="flex flex-1 items-center justify-center text-surface-400"
    >
      加载中...
    </div>
    <div
      v-else-if="error"
      class="flex flex-1 items-center justify-center text-red-400"
    >
      {{ error }}
    </div>
    <div
      v-else
      ref="scrollRef"
      class="flex-1 overflow-y-auto p-4"
    >
      <div class="mx-auto flex max-w-full flex-col items-center gap-4">
        <div
          v-for="n in numPages"
          :key="n"
          :ref="setPageEl(n - 1)"
          :data-page="n"
          class="flex w-full items-start justify-center"
          :style="{ minHeight: `${Math.min(1200, 800 * estimatedRatio * scale)}px` }"
        />
      </div>
    </div>

    <!-- 工具条 -->
    <div
      v-if="!loading && !error"
      class="pointer-events-none absolute bottom-4 left-1/2 -translate-x-1/2"
    >
      <div class="pointer-events-auto flex items-center gap-2 rounded-full bg-surface-800/90 px-4 py-2 text-sm text-surface-200 shadow-lg backdrop-blur">
        <input
          v-model="pageInput"
          class="w-10 rounded bg-surface-700 px-1 py-0.5 text-center text-white outline-none"
          @keydown.enter="jumpToPage"
          @blur="jumpToPage"
        >
        <span>/ {{ numPages }}</span>
        <button
          class="rounded-full p-1.5 transition-colors hover:bg-white/10"
          @click="zoom(-0.2)"
        >
          <ZoomOut class="h-4 w-4" />
        </button>
        <span class="w-12 text-center">{{ Math.round(scale * 100) }}%</span>
        <button
          class="rounded-full p-1.5 transition-colors hover:bg-white/10"
          @click="zoom(0.2)"
        >
          <ZoomIn class="h-4 w-4" />
        </button>
      </div>
    </div>
  </div>
</template>
