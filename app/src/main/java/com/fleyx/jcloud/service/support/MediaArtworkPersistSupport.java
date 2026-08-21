package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.common.enums.MediaMetadataSource;
import com.fleyx.jcloud.common.enums.MediaPersistStatus;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaMovieFile;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.util.FileHashUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.UserReadWriteLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 媒体元数据写回支撑组件（ADR 0020 / issue #20）：削刮成功后把 NFO 与图片写回视频所在目录。
 * <p>
 * 低层原语（文件查找/读取/NFO 与图片写回为正式 FileNode）与新模型编排（persistMovieV2/persistSeriesV2）
 * 合并为一层（票据 10）：本地来源写物理文件 + 插入/覆盖 FileNode，计入用户已用空间；远程来源经远程
 * 上传通道落盘。已存在同名 FileNode 则覆盖更新，不触发用户冲突流程。任一步失败仅将对应元数据标记
 * persist_status=failed，不影响削刮主流程。TMDB 来源图片从 TMDB 下载（w500/w1280）；local_nfo 来源
 * 同样执行写回（ADR 0023），图片沿用本地已存在文件、缺失才下载。
 * 图片写回分两模式（工单 06）：非强制走 ensureArtworkIfMissing——已存在的本地图片文件直接沿用不覆盖，
 * 缺失的按 rawJson 下载；强制（force=true）走 ensureArtwork——总是按 rawJson 重新下载并覆盖同名文件
 * （图片产物全量替换）。NFO 写回走合并写（ADR 0033）：已存在 NFO 仅覆盖 jcloud 管理字段、保留外部工具
 * 未知元素，缺失时才全新生成；解析/读取失败回退整体重写。
 * 电影写 {@code movie.nfo} + folder.jpg/backdrop.jpg（ADR 0022）；剧写
 * tvshow.nfo + folder.jpg/backdrop.jpg + 季海报（seasonXX-poster.jpg）+ 逐集 nfo 与剧照（{@code <视频名>-thumb.jpg}）。
 * 旧模型（t_media_item / 旧 t_media_series）写回方法已随 issue #21 弃表删除。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaArtworkPersistSupport {

    private static final String IMAGE_MIME = "image/jpeg";

    private final FileMapper fileMapper;
    private final FileNodeSupport fileNodeSupport;
    private final FilePathSupport filePathSupport;
    private final UserSpaceSupport userSpaceSupport;
    private final UserUsedSpaceSupport userUsedSpaceSupport;
    private final RemoteFileService remoteFileService;
    private final UserReadWriteLock userReadWriteLock;
    private final MediaNfoSupport nfoSupport;
    private final TmdbService tmdbService;
    private final ObjectMapper objectMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaEpisodeMapper mediaEpisodeMapper;
    private final MediaEpisodeFileMapper mediaEpisodeFileMapper;
    private final MediaPlaybackResolveSupport mediaPlaybackResolveSupport;

    /**
     * 查找目录下的同名子文件节点。
     */
    public FileNode findChildFile(String userId, String parentId, String name) {
        return fileNodeSupport.findExistingChild(userId, parentId, name);
    }

    /**
     * 按名称顺序查找目录下第一个存在的子文件节点，全部不存在返回 null。
     */
    public FileNode findFirstChildFile(String userId, String parentId, List<String> names) {
        for (String name : names) {
            FileNode node = findChildFile(userId, parentId, name);
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    /**
     * 读取文件节点字节（本地直读，远程经适配器下载），失败返回 null。
     */
    public byte[] readFileBytes(FileNode node) {
        try {
            if (FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
                try (InputStream in = remoteFileService.download(node, node.getUserId()).getInputStream()) {
                    return in.readAllBytes();
                }
            }
            User user = userSpaceSupport.requireUser(node.getUserId());
            StorageSpace space = userSpaceSupport.requireSpace(node.getStorageSpaceId());
            Path path = FilePathUtil.resolvePhysicalPath(node,
                    filePathSupport.buildResolveContext(node, user.getUsername(), space));
            return Files.readAllBytes(path);
        } catch (Exception e) {
            log.warn("媒体附属文件读取失败: {}, {}", node.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * 将 XML 内容写为目标目录的 NFO 文件（覆盖同名），供新模型写回复用（issue #20）。
     */
    public FileNode writeNfoXml(FileNode dir, String name, String xml) {
        return writeFileNode(dir, name, xml.getBytes(StandardCharsets.UTF_8), nfoSupport.nfoMimeType());
    }

    /**
     * 合并写回 NFO（ADR 0033）：目标 NFO 已存在时先读出内容、经 {@link MediaNfoSupport#mergeNfo} 仅覆盖
     * jcloud 管理字段并保留外部工具写入的未知元素；不存在或读取/解析失败回退整体生成。电影/剧/集三级写回复用。
     */
    public FileNode writeNfoXml(FileNode dir, String name, MediaMetadata metadata, Integer seasonNo, Integer episodeNo) {
        FileNode existing = findChildFile(dir.getUserId(), dir.getId(), name);
        String existingXml = existing == null ? null : readExistingNfo(existing);
        String xml = nfoSupport.mergeNfo(existingXml, metadata, seasonNo, episodeNo);
        return writeFileNode(dir, name, xml.getBytes(StandardCharsets.UTF_8), nfoSupport.nfoMimeType());
    }

    private String readExistingNfo(FileNode node) {
        byte[] bytes = readFileBytes(node);
        if (bytes == null) {
            log.debug("NFO 合并读取失败，回退整体重写: {}", node.getName());
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 确保图片文件存在并返回其节点：rawJson 含 TMDB 图片路径则下载写入（已存在同名节点则覆盖），
     * 否则仅返回已有节点（local_nfo 来源与无图片路径场景）。供新模型写回复用（issue #20）。
     */
    public FileNode ensureArtwork(FileNode dir, String name, String rawJson, String jsonField, String kind) {
        FileNode existing = findChildFile(dir.getUserId(), dir.getId(), name);
        String tmdbPath = extractJsonField(rawJson, jsonField);
        if (tmdbPath == null) {
            return existing;
        }
        byte[] bytes = tmdbService.downloadArtwork(tmdbPath, kind);
        if (bytes == null) {
            throw new IllegalStateException("TMDB 图片下载失败: " + tmdbPath);
        }
        return writeFileNode(dir, name, bytes, IMAGE_MIME);
    }

    /**
     * 确保图片文件存在并返回其节点：目标文件已存在（{@link #findChildFile} 命中）则直接返回现有节点，
     * 不下载；缺失才走 {@link #ensureArtwork} 下载写回。非强制路径用；强制全量替换的覆盖版由后续工单处理。
     */
    public FileNode ensureArtworkIfMissing(FileNode dir, String name, String rawJson, String jsonField, String kind) {
        FileNode existing = findChildFile(dir.getUserId(), dir.getId(), name);
        if (existing != null) {
            return existing;
        }
        return ensureArtwork(dir, name, rawJson, jsonField, kind);
    }

    /**
     * 将字节内容写为正式 FileNode（覆盖同名文件，不触发用户冲突流程），按用户写锁串行。
     */
    public FileNode writeFileNode(FileNode dir, String name, byte[] content, String mimeType) {
        RLock lock = userReadWriteLock.writeLock(dir.getUserId());
        lock.lock();
        try {
            if (FileNodeConstants.SOURCE_REMOTE.equals(dir.getSourceType())) {
                return remoteFileService.uploadContent(dir, dir.getUserId(), name, content, mimeType);
            }
            return writeLocalFileNode(dir, name, content, mimeType);
        } finally {
            lock.unlock();
        }
    }

    private FileNode writeLocalFileNode(FileNode dir, String name, byte[] content, String mimeType) {
        String userId = dir.getUserId();
        User user = userSpaceSupport.requireUser(userId);
        StorageSpace space = userSpaceSupport.requireSpace(user);
        FileNode existing = findChildFile(userId, dir.getId(), name);
        if (existing != null && FileNodeConstants.TYPE_FOLDER.equals(existing.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "写回目标与同名文件夹冲突: " + name);
        }
        long oldSize = existing == null || existing.getSize() == null ? 0L : existing.getSize();
        long delta = content.length - oldSize;
        long quota = user.getQuota() == null ? 0L : user.getQuota();
        long usedSpace = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        if (delta > 0 && quota > 0 && usedSpace + delta > quota) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户配额不足");
        }
        String parentPathName = filePathSupport.resolveNamePath(dir, userId);
        Path physical = FilePathUtil.resolvePhysicalPath(space, user.getUsername(), parentPathName, name);
        try {
            Files.createDirectories(physical.getParent());
            Files.write(physical, content);
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "NFO/图片物理写入失败", e);
        }
        String hash;
        try {
            hash = FileHashUtil.identityHash(physical);
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "NFO/图片 hash 计算失败", e);
        }
        if (existing != null) {
            FileNode update = new FileNode();
            update.setId(existing.getId());
            update.setSize((long) content.length);
            update.setHash(hash);
            update.setMimeType(mimeType);
            update.setLastModified(System.currentTimeMillis());
            fileMapper.updateById(update);
            userUsedSpaceSupport.addUsedSpace(userId, delta);
            existing.setSize((long) content.length);
            return existing;
        }
        FileNode node = fileNodeSupport.buildFileNode(userId, dir.getId(), name, content.length, hash,
                space.getId(), mimeType);
        node.setLastModified(System.currentTimeMillis());
        fileNodeSupport.setNodePath(node, dir.getId());
        fileMapper.insert(node);
        userUsedSpaceSupport.addUsedSpace(userId, content.length);
        return node;
    }

    private String extractJsonField(String rawJson, String field) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            JsonNode value = objectMapper.readTree(rawJson).path(field);
            return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
        } catch (Exception e) {
            log.warn("元数据原始响应解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 写回电影级元数据：{@code movie.nfo} + folder.jpg/backdrop.jpg 到电影文件夹
     * （local_nfo 来源同样整体写回，ADR 0023）。非强制模式（force=false）。
     * 任一步失败仅标记 failed 不影响削刮结果。
     */
    public void persistMovieV2(MediaMovie movie, MediaMetadata metadata) {
        persistMovieV2(movie, metadata, false);
    }

    /**
     * 写回电影级元数据：{@code movie.nfo} + folder.jpg/backdrop.jpg 到电影文件夹
     * （local_nfo 来源同样整体写回，ADR 0023）。force=true 时图片按 rawJson 重新下载
     * 覆盖同名文件（产物全量替换，工单 06），false 沿用已有绑定/已存在文件、缺失才下载。
     * 任一步失败仅标记 failed 不影响削刮结果。
     */
    public void persistMovieV2(MediaMovie movie, MediaMetadata metadata, boolean force) {
        try {
            FileNode folder = fileMapper.selectById(movie.getFolderNodeId());
            if (folder == null) {
                throw new IllegalStateException("电影文件夹节点不存在: " + movie.getFolderNodeId());
            }
            MediaMovieFile file = mediaPlaybackResolveSupport.pickMovieFile(movie);
            FileNode video = file == null ? null : fileMapper.selectById(file.getFileNodeId());
            if (video == null) {
                throw new IllegalStateException("电影视频文件节点不存在: " + movie.getId());
            }
            writeNfoXml(folder, MediaNfoSupport.MOVIE_NFO, metadata, null, null);
            FileNode poster = ensureArtwork(force, metadata.getPosterFileNodeId(), folder,
                    MediaNfoSupport.POSTER_WRITE_NAME, metadata.getRawJson(), "poster_path", "poster");
            FileNode fanart = ensureArtwork(force, metadata.getBackdropFileNodeId(), folder,
                    MediaNfoSupport.BACKDROP_WRITE_NAME, metadata.getRawJson(), "backdrop_path", "backdrop");
            if (poster != null) {
                metadata.setPosterFileNodeId(poster.getId());
            }
            if (fanart != null) {
                metadata.setBackdropFileNodeId(fanart.getId());
            }
            metadata.setPersistStatus(MediaPersistStatus.PERSISTED.getCode());
        } catch (Exception e) {
            log.warn("电影元数据写回失败: movie={}, error={}", movie.getId(), e.getMessage());
            metadata.setPersistStatus(MediaPersistStatus.FAILED.getCode());
        } finally {
            mediaMetadataMapper.updateById(metadata);
        }
    }

    /**
     * 写回剧级元数据：tvshow.nfo + folder.jpg/backdrop.jpg（剧文件夹）、季海报、逐集 nfo 与剧照
     * （local_nfo 来源同样执行写回，ADR 0023）。非强制模式（force=false）。
     * 各级别独立成败：剧级失败不影响季/集继续写回。
     */
    public void persistSeriesV2(MediaSeries series, MediaMetadata seriesMetadata) {
        persistSeriesV2(series, seriesMetadata, false);
    }

    /**
     * 写回剧级元数据：tvshow.nfo + folder.jpg/backdrop.jpg（剧文件夹）、季海报、逐集 nfo 与剧照
     * （local_nfo 来源同样执行写回，ADR 0023）。force=true 时各级图片按 rawJson 重新下载覆盖
     * （产物全量替换，工单 06），false 沿用已有文件、缺失才下载。
     * 各级别独立成败：剧级失败不影响季/集继续写回。
     */
    public void persistSeriesV2(MediaSeries series, MediaMetadata seriesMetadata, boolean force) {
        FileNode seriesFolder = null;
        boolean ok = true;
        try {
            seriesFolder = fileMapper.selectById(series.getFolderNodeId());
            if (seriesFolder == null) {
                throw new IllegalStateException("剧文件夹节点不存在: " + series.getFolderNodeId());
            }
            writeNfoXml(seriesFolder, MediaNfoSupport.TVSHOW_NFO, seriesMetadata, null, null);
            FileNode poster = ensureArtwork(force, seriesMetadata.getPosterFileNodeId(), seriesFolder,
                    MediaNfoSupport.POSTER_WRITE_NAME, seriesMetadata.getRawJson(), "poster_path", "poster");
            FileNode fanart = ensureArtwork(force, seriesMetadata.getBackdropFileNodeId(), seriesFolder,
                    MediaNfoSupport.BACKDROP_WRITE_NAME, seriesMetadata.getRawJson(), "backdrop_path", "backdrop");
            if (poster != null) {
                seriesMetadata.setPosterFileNodeId(poster.getId());
            }
            if (fanart != null) {
                seriesMetadata.setBackdropFileNodeId(fanart.getId());
            }
        } catch (Exception e) {
            ok = false;
            log.warn("剧元数据写回失败: series={}, error={}", series.getId(), e.getMessage());
        }
        seriesMetadata.setPersistStatus(ok ? MediaPersistStatus.PERSISTED.getCode() : MediaPersistStatus.FAILED.getCode());
        mediaMetadataMapper.updateById(seriesMetadata);
        if (seriesFolder == null) {
            return;
        }
        persistSeasonPosters(series, seriesFolder, force);
        persistEpisodeNfos(series, force);
    }

    private void persistSeasonPosters(MediaSeries series, FileNode seriesFolder, boolean force) {
        List<MediaSeason> seasons = mediaSeasonMapper.selectList(
                new LambdaQueryWrapper<MediaSeason>().eq(MediaSeason::getSeriesId, series.getId()));
        for (MediaSeason season : seasons) {
            if (season.getSeasonNo() == null || season.getMetadataId() == null) {
                continue;
            }
            MediaMetadata metadata = mediaMetadataMapper.selectById(season.getMetadataId());
            if (metadata == null) {
                continue;
            }
            try {
                FileNode poster = ensureArtwork(force, metadata.getPosterFileNodeId(), seriesFolder,
                        nfoSupport.seasonPosterName(season.getSeasonNo()),
                        metadata.getRawJson(), "poster_path", "poster");
                if (poster != null) {
                    metadata.setPosterFileNodeId(poster.getId());
                }
                metadata.setPersistStatus(MediaPersistStatus.PERSISTED.getCode());
            } catch (Exception e) {
                log.warn("季海报写回失败: season={}, error={}", season.getId(), e.getMessage());
                metadata.setPersistStatus(MediaPersistStatus.FAILED.getCode());
            }
            mediaMetadataMapper.updateById(metadata);
        }
    }

    private void persistEpisodeNfos(MediaSeries series, boolean force) {
        List<MediaEpisode> episodes = mediaEpisodeMapper.selectList(new LambdaQueryWrapper<MediaEpisode>()
                .eq(MediaEpisode::getSeriesId, series.getId())
                .isNotNull(MediaEpisode::getMetadataId));
        for (MediaEpisode episode : episodes) {
            MediaMetadata metadata = mediaMetadataMapper.selectById(episode.getMetadataId());
            if (metadata == null || !MediaMetadataOwnerType.EPISODE.getCode().equals(metadata.getOwnerType())) {
                continue;
            }
            try {
                MediaEpisodeFile file = mediaPlaybackResolveSupport.pickEpisodeFile(episode);
                FileNode video = file == null ? null : fileMapper.selectById(file.getFileNodeId());
                FileNode dir = video == null ? null : fileMapper.selectById(video.getParentId());
                if (video == null || dir == null) {
                    throw new IllegalStateException("集视频文件节点不存在: " + episode.getId());
                }
                writeNfoXml(dir, nfoSupport.nfoNameOf(video.getName()), metadata,
                        episodeSeasonNo(episode), episode.getEpisodeNo());
                FileNode thumb = ensureArtwork(force, metadata.getPosterFileNodeId(), dir,
                        nfoSupport.episodeThumbNameOf(video.getName()),
                        metadata.getRawJson(), "still_path", "poster");
                if (thumb == null) {
                    thumb = ensureArtwork(force, metadata.getPosterFileNodeId(), dir,
                            nfoSupport.episodeThumbNameOf(video.getName()),
                            metadata.getRawJson(), "poster_path", "poster");
                }
                if (thumb != null) {
                    metadata.setPosterFileNodeId(thumb.getId());
                }
                metadata.setPersistStatus(MediaPersistStatus.PERSISTED.getCode());
            } catch (Exception e) {
                log.warn("集元数据写回失败: episode={}, error={}", episode.getId(), e.getMessage());
                metadata.setPersistStatus(MediaPersistStatus.FAILED.getCode());
            }
            mediaMetadataMapper.updateById(metadata);
        }
    }

    private Integer episodeSeasonNo(MediaEpisode episode) {
        MediaSeason season = mediaSeasonMapper.selectById(episode.getSeasonId());
        return season == null ? null : season.getSeasonNo();
    }

    /**
     * 图片产物写回（两模式，工单 06）：force=false 时元数据行已绑定且文件节点存活的图片直接沿用
     * （本地优先，ADR 0023——本地识别链图片如 poster.jpg 不被写回名 folder.jpg 覆盖），否则走
     * IfMissing 语义下载写回（缺失才下载）；force=true 时按 rawJson 重新下载并覆盖写回文件名
     * （全量替换，不沿用既有绑定/既有文件）。
     *
     * @param force       是否强制全量替换（true 总是下载覆盖）
     * @param boundNodeId 元数据行已绑定的图片节点 ID（posterFileNodeId/backdropFileNodeId），可为空
     * @param dir         目标目录
     * @param writeName   写回文件名（未绑定时按此名下载）
     * @param rawJson     元数据原始响应（含 TMDB 图片路径）
     * @param jsonField   图片路径字段（poster_path/backdrop_path/still_path）
     * @param kind        图片用途（poster/backdrop/still）
     */
    private FileNode ensureArtwork(boolean force, String boundNodeId, FileNode dir, String writeName,
                                   String rawJson, String jsonField, String kind) {
        if (!force) {
            FileNode bound = boundNodeId == null ? null : fileMapper.selectById(boundNodeId);
            if (bound != null) {
                return bound;
            }
            return ensureArtworkIfMissing(dir, writeName, rawJson, jsonField, kind);
        }
        return ensureArtwork(dir, writeName, rawJson, jsonField, kind);
    }
}
