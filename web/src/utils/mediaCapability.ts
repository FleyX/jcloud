/**
 * 浏览器原生 <video> 解码能力探测（直放可行性）
 * - 容器/编码缺失或未收录时保守放行（true，不降级）；h264 恒可解不探测；
 *   其余编码映射为 canPlayType 探测 MIME，返回非空字符串即视为可解码。
 * - 结果按会话缓存（模块级 Map），同一 容器+编码 组合不重复探测。
 * 与 useTranscodeSession 的 REMUX_PROBE_MIME 为同源知识（MSE 转封装探测），
 * 但直放走原生元素（canPlayType）而非 MediaSource.isTypeSupported，两表各自保留避免跨层耦合。
 */

/** 容器 → canPlayType 容器 MIME；未收录的容器放行不探测 */
const CONTAINER_MIME: Record<string, string> = {
  mp4: 'video/mp4',
  mov: 'video/mp4',
  m4v: 'video/mp4',
  webm: 'video/webm',
}

/** 视频编码 → canPlayType codecs 标签；未收录的编码放行不探测 */
const VIDEO_CODEC_TAG: Record<string, string> = {
  hevc: 'hvc1.1.6.L120.90',
  vp9: 'vp09.00.10.08',
  av1: 'av01.0.04M.08',
  vp8: 'vp8',
}

/** 探测结果缓存（会话级：页面生命周期内有效），key 为 `container|codec` 小写 */
const probeCache = new Map<string, boolean>()

/**
 * 浏览器原生 <video> 是否能解码该 容器+视频编码（直放可行性探测，结果按会话缓存）。
 * 无法判断（参数缺失/未收录）时一律放行，保持现有直放行为。
 */
export function canNativeDirectPlay(container: string | null, videoCodec: string | null): boolean {
  const containerKey = container?.toLowerCase() ?? ''
  const codecKey = videoCodec?.toLowerCase() ?? ''
  // 容器/编码缺失或 h264：无需判断，放行直放
  if (!containerKey || !codecKey || codecKey === 'h264') return true
  const containerMime = CONTAINER_MIME[containerKey]
  const codecTag = VIDEO_CODEC_TAG[codecKey]
  // 未收录的容器/编码：无法对应探测 MIME，保守放行不降级
  if (!containerMime || !codecTag) return true
  const cacheKey = `${containerKey}|${codecKey}`
  const cached = probeCache.get(cacheKey)
  if (cached !== undefined) return cached
  const supported = document.createElement('video')
    .canPlayType(`${containerMime}; codecs="${codecTag}"`) !== ''
  probeCache.set(cacheKey, supported)
  return supported
}