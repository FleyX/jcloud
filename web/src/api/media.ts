import { del, get, post, put } from './request'
import type { PageResult } from '@/types/auth'
import type {
  MediaDirectorySaveDto,
  MediaDirectoryVo,
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

// ---------- 匹配与进度 ----------

export function updateMediaMatch(id: string, tmdbId: number, mediaType: 'movie' | 'tv'): Promise<MediaItemVo> {
  return put<MediaItemVo>(`/media/items/${id}/match`, { tmdbId, mediaType })
}

export function updateSeriesMatch(seriesName: string, tmdbId: number): Promise<void> {
  return put<void>('/media/items/series/match', { tmdbId, mediaType: 'tv' }, { seriesName })
}

export function updateMediaProgress(id: string, progressMs: number): Promise<void> {
  return put<void>(`/media/items/${id}/progress`, { progressMs })
}

// ---------- 播放 ----------

export function fetchPlaybackInfo(id: string): Promise<MediaPlaybackInfoVo> {
  return get<MediaPlaybackInfoVo>(`/media/items/${id}/playback`)
}

export function createTranscodeSession(
  id: string,
  startMs: number,
  audioIndex?: number,
): Promise<MediaTranscodeSessionVo> {
  return post<MediaTranscodeSessionVo>(`/media/items/${id}/transcode`, undefined, { startMs, audioIndex })
}

export function subtitleUrl(id: string, index: number): string {
  return withToken(`/jcloud/api/media/items/${id}/subtitles/${index}`)
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
