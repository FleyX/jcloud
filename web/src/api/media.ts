import { del, get, post, put } from './request'
import type { PageResult } from '@/types/auth'
import type {
  MediaDirectorySaveDto,
  MediaDirectoryVo,
  MediaFavoriteOwnerType,
  MediaFavoriteQuery,
  MediaFavoriteVo,
  MediaGenreVo,
  MediaGlobalSearchResult,
  MediaHomeVo,
  MediaItemDetailVo,
  MediaItemVo,
  MediaPageQuery,
  MediaPlaybackInfoVo,
  MediaSeriesDetailVo,
  MediaSeriesVo,
  MediaTranscodeSessionVo,
  MediaType,
  TmdbConfigDto,
  TmdbSearchResultVo,
  TranscodeConfigDto,
} from '@/types/media'

/**
 * 拼接带 token 的媒体资源地址（video/img 标签无法携带 Authorization 头）
 */
export function withToken(url: string): string {
  const token = localStorage.getItem('jcloud_token') || ''
  const separator = url.includes('?') ? '&' : '?'
  return `${url}${separator}token=${encodeURIComponent(token)}`
}

// ---------- 目录管理 ----------

export function fetchMediaDirectories(): Promise<MediaDirectoryVo[]> {
  return get<MediaDirectoryVo[]>('/media/directories')
}

export function createMediaDirectory(dto: MediaDirectorySaveDto): Promise<MediaDirectoryVo> {
  return post<MediaDirectoryVo>('/media/directories', dto)
}

export function updateMediaDirectory(id: string, dto: MediaDirectorySaveDto): Promise<MediaDirectoryVo> {
  return put<MediaDirectoryVo>(`/media/directories/${id}`, dto)
}

export function deleteMediaDirectory(id: string): Promise<void> {
  return del<void>(`/media/directories/${id}`)
}

export function scanMediaDirectory(id: string): Promise<void> {
  return post<void>(`/media/directories/${id}/scan`)
}

export function scrapeMediaDirectory(id: string, force = false): Promise<void> {
  return post<void>(`/media/directories/${id}/scrape?force=${force}`)
}

// ---------- 海报墙 ----------

export function fetchMediaHome(): Promise<MediaHomeVo> {
  return get<MediaHomeVo>('/media/home')
}

export function fetchMediaMovies(query: MediaPageQuery): Promise<PageResult<MediaItemVo>> {
  return get<PageResult<MediaItemVo>>('/media/items/movies', query as Record<string, unknown>)
}

export function fetchMediaSeries(query: MediaPageQuery): Promise<PageResult<MediaSeriesVo>> {
  return get<PageResult<MediaSeriesVo>>('/media/items/series', query as Record<string, unknown>)
}

export function fetchMediaEpisodes(seriesId: string): Promise<MediaItemVo[]> {
  return get<MediaItemVo[]>(`/media/items/series/${seriesId}/episodes`)
}

export function fetchMediaOthers(query: MediaPageQuery): Promise<PageResult<MediaItemVo>> {
  return get<PageResult<MediaItemVo>>('/media/items/others', query as Record<string, unknown>)
}

// ---------- 类型 ----------

/**
 * 聚合媒体库类型列表（类型页）：名称 + 条目数 + 代表海报。
 */
export function fetchMediaGenres(directoryId: string): Promise<MediaGenreVo[]> {
  return get<MediaGenreVo[]>(`/media/libraries/${directoryId}/genres`)
}

// ---------- 全局搜索 ----------

/**
 * 全局搜索：跨该用户全部媒体库搜索，按电影/剧集/其他分组返回（各组前 N 条 + 总数）。
 */
export function searchMedia(keyword: string, size?: number): Promise<MediaGlobalSearchResult> {
  return get<MediaGlobalSearchResult>('/media/search', { keyword, size })
}

// ---------- 匹配与进度 ----------

/**
 * 手动修正匹配：id 为电影行或剧集行 ID（集级修正已下线，集 ID 会触发业务异常）。
 */
export function updateMediaMatch(id: string, tmdbId: number, mediaType: 'movie' | 'tv'): Promise<MediaItemVo> {
  return put<MediaItemVo>(`/media/items/${id}/match`, { tmdbId, mediaType })
}

/** 播放进度上报入参（versionId 非空时后端记为该次播放版本，续播据此定位；剧集/其他忽略） */
export interface MediaProgressUpdateDto {
  progressMs: number
  versionId?: string
}

export function updateMediaProgress(id: string, progressMs: number, versionId?: string): Promise<void> {
  const body: MediaProgressUpdateDto = { progressMs }
  if (versionId) body.versionId = versionId
  return put<void>(`/media/items/${id}/progress`, body)
}

// ---------- 播放 ----------

/**
 * 拉取播放信息。versionId 为电影版本明细行 ID（可选）：指定时用该版本文件事实，
 * 缺省时后端按续播语义定位（last_play_file_id 优先，缺省最早版本）。
 */
export function fetchPlaybackInfo(id: string, versionId?: string): Promise<MediaPlaybackInfoVo> {
  return get<MediaPlaybackInfoVo>(`/media/items/${id}/playback`, versionId ? { versionId } : undefined)
}

/**
 * 转码会话可选参数
 */
export interface TranscodeSessionOptions {
  /** 音轨序号（转码时选择音轨） */
  audioIndex?: number
  /** 目标码率 kbps，传入则转码并限码率 */
  targetBitrateKbps?: number
  /** 最大高度（仅允许 2160/1080/720/480/360），不放大降分辨率 */
  maxHeight?: number
  /** 视频流不支持 MSE 转封装时强制视频转码 */
  forceVideoTranscode?: boolean
}

export function createTranscodeSession(
  id: string,
  startMs: number,
  options: TranscodeSessionOptions = {},
  versionId?: string,
): Promise<MediaTranscodeSessionVo> {
  return post<MediaTranscodeSessionVo>(`/media/items/${id}/transcode`, undefined, { startMs, ...options, versionId })
}

/**
 * 转码会话心跳：播放页打开期间每 5s 一次，超时未心跳后端自动回收会话。
 * 原生 fetch 静默失败（如服务重启会话已回收属正常），不走统一异常提示。
 */
export function transcodeHeartbeat(sessionId: string): void {
  const token = localStorage.getItem('jcloud_token') || ''
  fetch(`/jcloud/api/media/transcode/${sessionId}/heartbeat`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
  }).catch(() => {})
}

/** 主动关闭转码会话（播放页退出），即时回收 ffmpeg 与缓存。原生 fetch 静默失败。 */
export function closeTranscodeSession(sessionId: string): void {
  const token = localStorage.getItem('jcloud_token') || ''
  fetch(`/jcloud/api/media/transcode/${sessionId}/close`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
    keepalive: true,
  }).catch(() => {})
}

/** sendBeacon 用关闭地址：页面卸载时无法带 Header，token 走查询参数（与分片请求一致） */
export function transcodeCloseBeaconUrl(sessionId: string): string {
  return withToken(`/jcloud/api/media/transcode/${sessionId}/close`)
}

/**
 * 构建字幕资源查询参数：版本参数与转码时间偏移可共存，offset 为 0 时不发送偏移参数。
 */
function buildSubtitleQuery(versionId?: string, offsetMs?: number): string {
  const params: string[] = []
  if (versionId) params.push(`versionId=${encodeURIComponent(versionId)}`)
  if (offsetMs && offsetMs > 0) params.push(`offsetMs=${offsetMs}`)
  return params.length > 0 ? `?${params.join('&')}` : ''
}

/**
 * 内嵌字幕 URL。offsetMs 为转码会话起点（毫秒），直放时不传（0）。
 */
export function subtitleUrl(id: string, index: number, versionId?: string, offsetMs?: number): string {
  const base = `/jcloud/api/media/items/${id}/subtitles/${index}`
  return withToken(`${base}${buildSubtitleQuery(versionId, offsetMs)}`)
}

/**
 * 外置字幕 URL。offsetMs 为转码会话起点（毫秒），直放时不传（0）。
 */
export function externalSubtitleUrl(id: string, subtitleId: string, versionId?: string, offsetMs?: number): string {
  const base = `/jcloud/api/media/items/${id}/subtitles/external/${subtitleId}`
  return withToken(`${base}${buildSubtitleQuery(versionId, offsetMs)}`)
}

// ---------- 元数据 ----------

export function searchTmdb(mediaType: MediaType, query: string, year?: number): Promise<TmdbSearchResultVo[]> {
  return get<TmdbSearchResultVo[]>('/media/tmdb/search', { mediaType, query, year })
}

// ---------- 详情 ----------

export function fetchItemDetail(id: string): Promise<MediaItemDetailVo> {
  return get<MediaItemDetailVo>(`/media/items/${id}/detail`)
}

export function fetchSeriesDetail(id: string): Promise<MediaSeriesDetailVo> {
  return get<MediaSeriesDetailVo>(`/media/series/${id}/detail`)
}

export function fetchSeasonEpisodes(seriesId: string, seasonId: string): Promise<MediaItemVo[]> {
  return get<MediaItemVo[]>(`/media/series/${seriesId}/seasons/${seasonId}/episodes`)
}

// ---------- 收藏 ----------

/**
 * 收藏/取消收藏切换：返回切换后的收藏状态（true 已收藏 / false 未收藏）。
 */
export function toggleFavorite(
  ownerType: MediaFavoriteOwnerType,
  ownerId: string,
): Promise<boolean> {
  return post<boolean>('/media/favorites/toggle', { ownerType, ownerId })
}

/**
 * 分页查询我的收藏（按 ownerType 分区独立分页，收藏时间倒序）。
 */
export function fetchMediaFavorites(query: MediaFavoriteQuery): Promise<PageResult<MediaFavoriteVo>> {
  return get<PageResult<MediaFavoriteVo>>('/media/favorites', query as unknown as Record<string, unknown>)
}

export function refreshMetadata(id: string): Promise<void> {
  return post<void>(`/media/metadata/${id}/refresh`)
}

// ---------- 管理端 ----------

export function fetchTmdbConfig(): Promise<TmdbConfigDto> {
  return get<TmdbConfigDto>('/admin/media/tmdb-config')
}

export function updateTmdbConfig(dto: TmdbConfigDto): Promise<void> {
  return put<void>('/admin/media/tmdb-config', dto)
}

export function fetchTranscodeConfig(): Promise<TranscodeConfigDto> {
  return get<TranscodeConfigDto>('/admin/media/transcode-config')
}

export function updateTranscodeConfig(dto: TranscodeConfigDto): Promise<void> {
  return put<void>('/admin/media/transcode-config', dto)
}
