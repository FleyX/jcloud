/**
 * 视频媒体库相关类型定义
 */
import type { PageResult } from '@/types/auth'

export type MediaType = 'movie' | 'tv' | 'other'
export type MediaItemType = 'movie' | 'episode' | 'other' | 'series'
export type MediaMatchStatus = 'matched' | 'manual' | 'unmatched' | 'none'
export type MediaScanStatus = 'SCANNING' | 'COMPLETED' | 'FAILED' | 'PARTIAL'
export type MediaSourceType = 'local' | 'remote'
/** 收藏归属实体类型（toggle 入参与 VO favorited 字段共用） */
export type MediaFavoriteOwnerType = 'movie' | 'series' | 'season' | 'episode' | 'other'

/**
 * 我的收藏条目视图（收藏页卡片，后端 Long 字段序列化为 string）
 */
export interface MediaFavoriteVo {
  /** 归属实体类型：movie / series / season / episode / other */
  ownerType: MediaFavoriteOwnerType
  /** 归属实体 ID */
  ownerId: string
  /** 收藏状态（收藏页恒为 true，心形取消后前端移除卡片） */
  favorited: boolean
  /** 收藏时间 */
  favoriteTime: string
  /** 展示标题（元数据标题或条目名；季/集由前端组合标题，可为空） */
  title: string | null
  /** 文件名/条目名（other 有效） */
  fileName: string | null
  posterUrl: string | null
  /** 关联文件节点 ID（other 有效，缩略图回退与播放用） */
  fileNodeId: string | null
  /** 时长（毫秒，other 有效） */
  durationMs: string | null
  releaseDate: string | null
  voteAverage: number | null
  matchStatus: MediaMatchStatus | null
  seriesId: string | null
  seriesName: string | null
  seasonNo: number | null
  episodeNo: number | null
}

/**
 * 我的收藏分页查询参数（按归属实体类型独立分页）
 */
export interface MediaFavoriteQuery {
  ownerType: MediaFavoriteOwnerType
  /** 媒体库 ID 过滤，为空表示全部收藏 */
  directoryId?: string
  pageNum?: number
  pageSize?: number
}

/**
 * 媒体列表分页查询参数（电影/电视/其他通用）
 */
export interface MediaPageQuery {
  pageNum?: number
  pageSize?: number
  keyword?: string
  /** 媒体库 ID 过滤，为空表示跨库 */
  directoryId?: string
  /** 类型筛选：匹配元数据 genres 拆分后包含该值的条目 */
  genre?: string
  sortField?: 'added' | 'release' | 'rating' | 'title'
  sortOrder?: 'asc' | 'desc'
}

/**
 * 类型聚合视图（类型页卡片）：Long 字段序列化为 string
 */
export interface MediaGenreVo {
  /** 类型名 */
  name: string
  /** 该类型下的条目数 */
  itemCount: string
  /** 代表海报 URL（该类型下任一条目的元数据海报，无海报为 null） */
  posterUrl: string | null
}

/**
 * 全局搜索结果：跨全部媒体库搜索，按电影/剧集/其他分组（各组前 N 条 + 总数）
 */
export interface MediaGlobalSearchResult {
  movies: PageResult<MediaItemVo>
  series: PageResult<MediaSeriesVo>
  others: PageResult<MediaItemVo>
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
 * 影视首页聚合视图（我的媒体 / 继续观看 / 接下来 / 最新电影 / 最新剧集）
 */
export interface MediaHomeVo {
  libraries: MediaDirectoryVo[]
  continueWatching: MediaItemVo[]
  nextUp: MediaItemVo[]
  /** 最新电影（按电影入库时间倒序，最多 16 条） */
  latestMovies: MediaItemVo[]
  /** 最新剧集（按剧集最近入库时间倒序，最多 16 条；剧集卡无代表文件） */
  latestSeries: MediaItemVo[]
}

export interface MediaItemVo {
  id: string
  /** 关联文件节点 ID；剧集聚合卡等无代表文件的标题卡为 null */
  fileNodeId: string | null
  itemType: MediaItemType
  /** 文件名；无代表文件的标题卡（剧集聚合卡）为 null */
  fileName: string | null
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
  /** 观看进度（毫秒），无进度记录为 null */
  progressMs: number | null
  lastPlayTime: string | null
  /** 入库时间（后端 yyyy-MM-dd HH:mm:ss，GMT+8）；最新电影为电影入库时间，最新剧集为剧集最近入库时间 */
  addedTime: string | null
  /** 元数据完整性（后端重算，前端已不展示弱标识），电影/剧集行有效 */
  metadataComplete: boolean
  /** 当前用户是否已收藏 */
  favorited: boolean
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
  /** 元数据完整性（后端重算，前端已不展示弱标识） */
  metadataComplete: boolean
  /** 当前用户是否已收藏 */
  favorited: boolean
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
  /** 本次解析使用的文件明细行 ID（电影为版本 ID），播放/进度上报据此定位版本；续播缺省解析时也可能为 null */
  versionId?: string
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
  /** 元数据完整性（后端重算，前端已不展示弱标识），电影/剧集详情有效 */
  metadataComplete: boolean
  /** 后端缺省播放版本 ID（电影：last_play_file_id 非空时取它，否则最早版本） */
  defaultVersionId?: string
  /** 电影版本列表（仅电影详情有效，其他类型为 null），create_time 升序 */
  versions: MediaMovieVersionVo[] | null
  /** 当前用户是否已收藏 */
  favorited: boolean
}

/**
 * 电影版本视图（电影详情页版本列表，一行一个视频文件明细）。
 * 后端 Long 字段序列化为 string，故 fileSize/durationMs 为 string。
 */
export interface MediaMovieVersionVo {
  /** 电影文件明细 ID */
  id: string
  /** 视频文件节点 ID */
  fileNodeId: string
  fileName: string | null
  fileSize: string | null
  durationMs: string | null
  /** 封装格式 */
  container: string | null
  videoCodec: string | null
  audioCodec: string | null
  width: number | null
  height: number | null
}

export interface MediaSeriesSeasonVo {
  seasonId: string
  /** 季号，为空表示未知季（排最后） */
  seasonNo: number | null
  posterUrl: string | null
  episodeCount: number
  /** 是否有观看进度（存在 progressMs > 0 的集） */
  hasProgress: boolean
  /** 当前用户是否已收藏 */
  favorited: boolean
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
  /** 元数据完整性（后端重算，前端已不展示弱标识） */
  metadataComplete: boolean
  /** 当前用户是否已收藏 */
  favorited: boolean
}

export interface MediaTranscodeSessionVo {
  sessionId: string
  playlistUrl: string
}
