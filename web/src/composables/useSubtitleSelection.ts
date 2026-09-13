/**
 * 字幕选择 composable
 * - 内嵌/外置字幕轨统一选择（subtitleKey + 选择项唯一标识）
 * - 默认字幕选择：defaulted 标记 > localStorage 语言偏好 > 无（applyDefaultSubtitle）
 * - selectSubtitle 记忆语言偏好，供后续条目按语言自动匹配
 * - activeSubtitle 计算：转码播放时按转码会话起点偏移字幕 URL，直放 offset 0
 * - 供 useMediaPlayback 编排：主 composable 只保留进度上报与直放/HLS 选择
 */
import { computed, ref, type Ref } from 'vue'
import type { MediaSubtitleItem } from '@/types/media'
import { externalSubtitleUrl, pureExternalSubtitleUrl, pureSubtitleUrl, subtitleUrl } from '@/api/media'

const SUBTITLE_LANG_STORAGE_KEY = 'jcloud.player.subtitlePref'

/**
 * 当前展示的字幕（「无」为 null）
 */
export interface ActiveSubtitle {
  key: string
  label: string
  src: string
}

/**
 * 字幕选择项唯一标识：embedded:{index} / external:{subtitleId}
 */
export function subtitleItemKey(item: MediaSubtitleItem): string {
  return item.type === 'embedded' ? `embedded:${item.index}` : `external:${item.subtitleId}`
}

/**
 * 位图字幕烧录参数（携带时后端强制视频转码并烧录该轨，二选一）
 */
export interface BurnInParams {
  /** 内嵌位图字幕轨序号 */
  subtitleIndex?: number
  /** 外部位图字幕记录 ID */
  externalSubtitleId?: string
}

export interface SubtitleSelectionDeps {
  /** 字幕列表来源（响应式，如 playbackInfo 派生 computed） */
  subtitles: Ref<MediaSubtitleItem[]>
  /** 已加载条目的 id（字幕 URL 构造必须使用它而非路由参数；纯播放为文件节点 ID） */
  itemId: Ref<string | null>
  /** 当前播放版本（电影为文件明细行 ID；剧集/其他为 null） */
  currentVersionId: Ref<string | null>
  /** 是否处于转码播放（转码时字幕 URL 携带会话起点偏移） */
  transcodeActive: Ref<boolean>
  /** 当前转码会话起点偏移（毫秒），直放时为 0 */
  transcodeBaseMs: Ref<number>
  /** 纯播放模式标记（未收录文件）：字幕 URL 走 files 形态（无版本参数） */
  pure: Ref<boolean>
}

export function useSubtitleSelection(deps: SubtitleSelectionDeps) {
  const { subtitles, itemId, currentVersionId, transcodeActive, transcodeBaseMs, pure } = deps

  const subtitleKey = ref<string | null>(null)

  const activeSubtitle = computed<ActiveSubtitle | null>(() => {
    const key = subtitleKey.value
    const id = itemId.value
    if (!key || !id) return null
    const item = subtitles.value.find((s) => subtitleItemKey(s) === key)
    if (!item) return null
    // 位图字幕不可作为 WebVTT track：不渲染 <track>，也不请求字幕 URL
    if (item.bitmap) return null
    const versionId = currentVersionId.value ?? undefined
    // 转码播放的字幕时间轴相对当前转码会话起点偏移，直放使用原片时间轴（offset 0）。
    // transcodeBaseMs 可能含小数（由 currentTime*1000 换算），后端 offsetMs 为整型，必须取整
    const offsetMs = transcodeActive.value ? Math.floor(transcodeBaseMs.value) : 0
    // 纯播放（未收录文件）走 files 形态 URL（无版本参数），已收录走 items 形态
    const src = item.type === 'embedded' && item.index !== null
      ? pure.value
        ? pureSubtitleUrl(id, item.index, offsetMs)
        : subtitleUrl(id, item.index, versionId, offsetMs)
      : item.subtitleId
        ? pure.value
          ? pureExternalSubtitleUrl(id, item.subtitleId, offsetMs)
          : externalSubtitleUrl(id, item.subtitleId, versionId, offsetMs)
        : null
    return src ? { key, label: item.label, src } : null
  })

  /**
   * 当前选中字幕的烧录参数：位图项返回参数对象（embedded → subtitleIndex，external → externalSubtitleId），
   * 文本项/「无」返回 null。携带时后端强制视频转码烧录。
   */
  const burnInSubtitle = computed<BurnInParams | null>(() => {
    const key = subtitleKey.value
    if (!key) return null
    const item = subtitles.value.find((s) => subtitleItemKey(s) === key)
    if (!item || !item.bitmap) return null
    if (item.type === 'embedded' && item.index !== null) return { subtitleIndex: item.index }
    if (item.type === 'external' && item.subtitleId) return { externalSubtitleId: item.subtitleId }
    return null
  })

  function selectSubtitle(key: string | null) {
    subtitleKey.value = key
    if (key) {
      const item = subtitles.value.find((s) => subtitleItemKey(s) === key)
      // 记忆语言偏好，新片按 language 或 label 匹配；位图字幕不参与偏好匹配，不记忆
      if (item && !item.bitmap) localStorage.setItem(SUBTITLE_LANG_STORAGE_KEY, item.language || item.label)
    }
  }

  /** track 元素加载后强制 showing（「无」时元素被移除即全部禁用） */
  function handleTrackLoad(event: Event) {
    const track = (event.target as HTMLTrackElement).track
    if (track) track.mode = 'showing'
  }

  /** 默认字幕：defaulted 标记 > localStorage 语言偏好 > 无（位图项不参与自动选中，仅可手动选择） */
  function applyDefaultSubtitle(list: MediaSubtitleItem[]) {
    const textList = list.filter((s) => !s.bitmap)
    const defaulted = textList.find((s) => s.defaulted)
    if (defaulted) {
      subtitleKey.value = subtitleItemKey(defaulted)
      return
    }
    const preferred = localStorage.getItem(SUBTITLE_LANG_STORAGE_KEY)
    if (preferred) {
      const match = textList.find((s) => s.language === preferred || s.label === preferred)
      if (match) {
        subtitleKey.value = subtitleItemKey(match)
        return
      }
    }
    subtitleKey.value = null
  }

  return {
    subtitleKey,
    activeSubtitle,
    burnInSubtitle,
    selectSubtitle,
    handleTrackLoad,
    applyDefaultSubtitle,
  }
}
