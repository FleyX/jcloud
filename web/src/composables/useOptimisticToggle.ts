/**
 * 已观看等「乐观切换」composable：存旧值 → 本地置位 → 请求 → 失败回滚。
 * - 供卡片/详情页的已观看 ✓ 切换复用（PosterCard / MovieDetail / SeriesDetail 及其拆出的 SeasonCard）
 * - 标记已观看时可同步清零本地进度，失败时一并回滚
 * - 成功后可选触发父级回调（如静默重拉详情保持聚合一致）；请求异常提示由统一请求层处理
 */
import { ref } from 'vue'

export interface UseOptimisticToggleOptions {
  /** 读取当前已观看状态 */
  isWatched: () => boolean
  /** 乐观置位：写入本地 watched（标记为已观看时由调用方经 progress.set 清零进度） */
  setWatched: (watched: boolean) => void
  /** 标记已观看时清零的进度字段描述：get 读当前进度、set 回滚进度（电影与集场景使用） */
  progress?: {
    get: () => number | null | undefined
    set: (value: number | null | undefined) => void
  }
  /** 调后端切换接口（当前条目 id、目标状态） */
  toggle: (watched: boolean) => Promise<unknown>
  /** 成功后回调（父级静默重拉详情使聚合一致） */
  onSuccess?: () => unknown | Promise<unknown>
}

export function useOptimisticToggle(options: UseOptimisticToggleOptions) {
  const toggling = ref(false)

  async function toggle() {
    const previousWatched = options.isWatched()
    const previousProgress = options.progress?.get()
    const next = !previousWatched
    options.setWatched(next)
    if (next && options.progress) options.progress.set(0)
    toggling.value = true
    try {
      await options.toggle(next)
      await options.onSuccess?.()
    } catch {
      options.setWatched(previousWatched)
      if (options.progress) options.progress.set(previousProgress ?? null)
    } finally {
      toggling.value = false
    }
  }

  return { toggle, toggling }
}
