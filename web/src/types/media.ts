/**
 * 视频媒体库相关类型定义
 */

export type MediaType = 'movie' | 'tv' | 'other'
export type MediaItemType = 'movie' | 'episode' | 'other'
export type MediaMatchStatus = 'matched' | 'manual' | 'unmatched' | 'none'
export type MediaScanStatus = 'SCANNING' | 'COMPLETED' | 'FAILED' | 'PARTIAL'

/**
 * 媒体列表分页查询参数（电影/电视/其他通用）
 */
export interface MediaPageQuery {
  pageNum?: number
  pageSize?: number
  keyword?: string
  sortField?: 'added' | 'release'
  sortOrder?: 'asc' | 'desc'
}

export interface MediaDirectoryVo {
  id: string
  fileNodeId: string
  name: string
  mediaType: MediaType
  scanCron: string | null
  lastScanTime: string | null
  lastScanStatus: string | null
  lastScanError: string | null
  itemCount: number
}

export interface MediaDirectorySaveDto {
  fileNodeId: string
  name?: string
  mediaType: MediaType
  scanCron?: string
}

export interface MediaItemVo {
  id: string
  fileNodeId: string
  itemType: MediaItemType
  fileName: string
  matchStatus: MediaMatchStatus
  metadataId: string | null
  title: string
  posterUrl: string | null
  releaseDate: string | null
  voteAverage: number | null
  durationMs: number | null
  seasonNo: number | null
  episodeNo: number | null
  progressMs: number
  lastPlayTime: string | null
}

export interface MediaSeriesVo {
  seriesName: string
  metadataId: string | null
  title: string
  posterUrl: string | null
  releaseDate: string | null
  voteAverage: number | null
  episodeCount: number
  matchStatus: MediaMatchStatus
  lastPlayTime: string | null
}

export interface MediaTrack {
  index: number
  codec: string
  language: string | null
  title: string | null
}

export interface MediaPlaybackInfoVo {
  mode: 'direct' | 'transcode'
  directUrl: string | null
  transcodeUrl: string | null
  durationMs: number | null
  container: string | null
  videoCodec: string | null
  audioCodec: string | null
  width: number | null
  height: number | null
  audioTracks: MediaTrack[]
  subtitleTracks: MediaTrack[]
  progressMs: number
}

export interface TmdbSearchResultVo {
  tmdbId: number
  mediaType: 'movie' | 'tv'
  title: string
  originalTitle: string | null
  releaseDate: string | null
  voteAverage: number | null
  overview: string | null
  posterUrl: string | null
}

export interface TmdbConfigDto {
  apiKey: string
  proxy: string
}

export type TranscodeHwaccel = 'auto' | 'vaapi' | 'qsv' | 'nvenc' | 'none'

export interface TranscodeConfigDto {
  hwaccel: TranscodeHwaccel
  device: string
  threads: number
}

export interface MediaItemDetailVo {
  id: string
  itemType: MediaItemType
  fileName: string
  fileSize: number | null
  matchStatus: MediaMatchStatus
  metadataId: string | null
  seriesName: string | null
  seasonNo: number | null
  episodeNo: number | null
  durationMs: number | null
  progressMs: number
  width: number | null
  height: number | null
  videoCodec: string | null
  audioCodec: string | null
  title: string
  originalTitle: string | null
  overview: string | null
  genres: string[]
  releaseDate: string | null
  voteAverage: number | null
  posterUrl: string | null
  backdropUrl: string | null
}

export interface MediaSeriesDetailVo {
  seriesName: string
  matchStatus: MediaMatchStatus
  metadataId: string | null
  title: string
  originalTitle: string | null
  overview: string | null
  genres: string[]
  releaseDate: string | null
  voteAverage: number | null
  seasonCount: number | null
  posterUrl: string | null
  backdropUrl: string | null
  episodes: MediaItemVo[]
}

export interface MediaTranscodeSessionVo {
  sessionId: string
  playlistUrl: string
}
