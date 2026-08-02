/**
 * 视频媒体库相关类型定义
 */

export type MediaType = 'movie' | 'tv' | 'other'
export type MediaItemType = 'movie' | 'episode' | 'other'
export type MediaMatchStatus = 'matched' | 'manual' | 'unmatched' | 'none'
export type MediaScanStatus = 'SCANNING' | 'COMPLETED' | 'FAILED' | 'PARTIAL'
export type MediaSourceType = 'local' | 'remote'

/**
 * 媒体列表分页查询参数（电影/电视/其他通用）
 */
export interface MediaPageQuery {
  pageNum?: number
  pageSize?: number
  keyword?: string
  /** 媒体库 ID 过滤，为空表示跨库 */
  directoryId?: string
  sortField?: 'added' | 'release'
  sortOrder?: 'asc' | 'desc'
}

export interface MediaDirectorySourceVo {
  id: string
  fileNodeId: string
  folderName: string
  sourceType: MediaSourceType
}

export interface MediaDirectoryVo {
  id: string
  name: string
  mediaType: MediaType
  scanCron: string | null
  lastScanTime: string | null
  lastScanStatus: string | null
  lastScanError: string | null
  lastScrapeTime: string | null
  lastScrapeStatus: string | null
  lastScrapeError: string | null
  itemCount: number
  sources: MediaDirectorySourceVo[]
  coverPosterUrl: string | null
}

export interface MediaDirectorySaveDto {
  /** 来源目录的文件夹节点 ID 列表，至少 1 个 */
  sourceFileNodeIds: string[]
  name?: string
  mediaType: MediaType
  scanCron?: string
}

/**
 * 影视首页聚合视图（我的媒体 / 继续观看 / 接下来）
 */
export interface MediaHomeVo {
  libraries: MediaDirectoryVo[]
  continueWatching: MediaItemVo[]
  nextUp: MediaItemVo[]
}

export interface MediaItemVo {
  id: string
  fileNodeId: string
  itemType: MediaItemType
  fileName: string
  matchStatus: MediaMatchStatus
  metadataId: string | null
  /** 所属剧 ID，仅 episode 有效 */
  seriesId: string | null
  /** 所属剧名，仅 episode 有效 */
  seriesName: string | null
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
  id: string
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

/**
 * 统一字幕项（内嵌字幕轨与外部字幕统一渲染选择列表）
 */
export interface MediaSubtitleItem {
  /** 字幕类型：embedded 内嵌轨 / external 外部字幕文件 */
  type: 'embedded' | 'external'
  /** 内嵌字幕轨序号（type=embedded 有效） */
  index: number | null
  /** 外部字幕记录 ID（type=external 有效） */
  subtitleId: string | null
  /** 展示名 */
  label: string
  /** 语言，未知为 null */
  language: string | null
  /** 是否默认字幕 */
  defaulted: boolean
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
  /** 统一字幕列表（内嵌在前按 index，外部在后按默认优先、标签排序） */
  subtitles: MediaSubtitleItem[]
  /** 实际码率 bps（Long 序列化为 string），缺失为 null */
  effectiveBitRate: string | null
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
  seriesId: string | null
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

export interface MediaSeriesSeasonVo {
  seasonId: string
  /** 季号，为空表示未知季（排最后） */
  seasonNo: number | null
  posterUrl: string | null
  episodeCount: number
  /** 是否有观看进度（存在 progressMs > 0 的集） */
  hasProgress: boolean
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
  seasons: MediaSeriesSeasonVo[]
}

export interface MediaTranscodeSessionVo {
  sessionId: string
  playlistUrl: string
}
