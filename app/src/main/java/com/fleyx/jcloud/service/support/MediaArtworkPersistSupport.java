package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.common.enums.MediaMetadataSource;
import com.fleyx.jcloud.common.enums.MediaPersistStatus;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectorySourceMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaMetadata;
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
 * 媒体元数据写回支撑组件（ADR 0020）：削刮成功后把 NFO 与图片写回视频所在目录。
 * <p>
 * 写回为正式 FileNode：本地来源写物理文件 + 插入/覆盖 FileNode（计入用户已用空间）；
 * 远程来源经远程上传通道落盘。已存在同名 FileNode 则覆盖更新，不触发用户冲突流程。
 * 任一步失败仅将对应元数据标记 persist_status=failed，不影响削刮主流程。
 * TMDB 来源图片从 TMDB 下载（w500/w1280）；local_nfo 来源的内容为用户提供、已在视频目录，
 * 跳过该级 nfo/图片写回（ADR 0020 完全信任本地）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaArtworkPersistSupport {

    private static final String IMAGE_MIME = "image/jpeg";

    private final FileMapper fileMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final MediaSeasonMapper mediaSeasonMapper;
    private final MediaItemMapper mediaItemMapper;
    private final MediaDirectorySourceMapper mediaDirectorySourceMapper;
    private final FileNodeSupport fileNodeSupport;
    private final FilePathSupport filePathSupport;
    private final UserSpaceSupport userSpaceSupport;
    private final RemoteFileService remoteFileService;
    private final UserReadWriteLock userReadWriteLock;
    private final MediaNfoSupport nfoSupport;
    private final TmdbService tmdbService;
    private final ObjectMapper objectMapper;

    /**
     * 查找目录下的同名子文件节点。
     */
    public FileNode findChildFile(String userId, String parentId, String name) {
        return fileNodeSupport.findExistingChild(userId, parentId, name);
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
     * 定位剧文件夹节点：取任一集条目的文件路径中，来源目录的下一级文件夹。
     */
    public FileNode resolveSeriesFolder(MediaSeries series) {
        MediaItem episode = mediaItemMapper.selectOne(new LambdaQueryWrapper<MediaItem>()
                .eq(MediaItem::getSeriesId, series.getId())
                .isNotNull(MediaItem::getSourceId)
                .orderByAsc(MediaItem::getId)
                .last("limit 1"));
        if (episode == null) {
            return null;
        }
        FileNode file = fileMapper.selectById(episode.getFileNodeId());
        MediaDirectorySource source = mediaDirectorySourceMapper.selectById(episode.getSourceId());
        if (file == null || source == null || file.getPath() == null) {
            return null;
        }
        String[] ids = file.getPath().split("\\" + FileNodeConstants.PATH_SEPARATOR);
        for (int i = 0; i < ids.length - 1; i++) {
            if (source.getFileNodeId().equals(ids[i])) {
                return fileMapper.selectById(ids[i + 1]);
            }
        }
        return null;
    }

    /**
     * 写回条目级元数据：电影写 {@code <主文件名>.nfo} + poster/fanart，
     * 集写 {@code <主文件名>.nfo} + {@code <主文件名>-thumb.jpg}。
     * local_nfo 来源的内容为用户提供、已在视频目录，跳过写回（ADR 0020）。
     */
    public void persistItem(MediaItem item, MediaMetadata metadata) {
        try {
            if (MediaMetadataSource.LOCAL_NFO.getCode().equals(metadata.getSource())) {
                metadata.setPersistStatus(MediaPersistStatus.PERSISTED.getCode());
                return;
            }
            FileNode videoFile = fileMapper.selectById(item.getFileNodeId());
            if (videoFile == null) {
                throw new IllegalStateException("视频文件节点不存在: " + item.getFileNodeId());
            }
            FileNode dir = fileMapper.selectById(videoFile.getParentId());
            boolean episode = MediaItemType.EPISODE.getCode().equals(item.getItemType());
            writeNfo(dir, nfoSupport.nfoNameOf(videoFile.getName()), metadata,
                    episode ? item.getSeasonNo() : null, episode ? item.getEpisodeNo() : null);
            if (episode) {
                String thumbName = nfoSupport.episodeThumbNameOf(videoFile.getName());
                FileNode thumb = ensureArtwork(dir, thumbName, metadata, "still_path", "poster");
                if (thumb == null) {
                    thumb = ensureArtwork(dir, thumbName, metadata, "poster_path", "poster");
                }
                if (thumb != null) {
                    metadata.setPosterFileNodeId(thumb.getId());
                }
            } else {
                FileNode poster = ensureArtwork(dir, MediaNfoSupport.POSTER_JPG, metadata, "poster_path", "poster");
                FileNode fanart = ensureArtwork(dir, MediaNfoSupport.FANART_JPG, metadata, "backdrop_path", "backdrop");
                if (poster != null) {
                    metadata.setPosterFileNodeId(poster.getId());
                }
                if (fanart != null) {
                    metadata.setBackdropFileNodeId(fanart.getId());
                }
            }
            metadata.setPersistStatus(MediaPersistStatus.PERSISTED.getCode());
        } catch (Exception e) {
            log.warn("条目元数据写回失败: item={}, error={}", item.getId(), e.getMessage());
            metadata.setPersistStatus(MediaPersistStatus.FAILED.getCode());
        } finally {
            mediaMetadataMapper.updateById(metadata);
        }
    }

    /**
     * 写回剧级元数据：tvshow.nfo + poster/fanart（剧文件夹）、季海报、逐集 nfo 与剧照。
     * 各级别独立成败：剧级失败不影响季/集继续写回；各级元数据来源为 local_nfo 时跳过本级写回
     * （内容为用户提供、已在视频目录，ADR 0020），其余级别按各自来源继续判定。
     */
    public void persistSeries(MediaSeries series, MediaMetadata seriesMetadata) {
        FileNode seriesFolder = null;
        boolean ok = true;
        try {
            seriesFolder = resolveSeriesFolder(series);
            if (seriesFolder == null) {
                throw new IllegalStateException("剧文件夹定位失败: " + series.getId());
            }
            if (!MediaMetadataSource.LOCAL_NFO.getCode().equals(seriesMetadata.getSource())) {
                writeNfo(seriesFolder, MediaNfoSupport.TVSHOW_NFO, seriesMetadata, null, null);
                FileNode poster = ensureArtwork(seriesFolder, MediaNfoSupport.POSTER_JPG,
                        seriesMetadata, "poster_path", "poster");
                FileNode fanart = ensureArtwork(seriesFolder, MediaNfoSupport.FANART_JPG,
                        seriesMetadata, "backdrop_path", "backdrop");
                if (poster != null) {
                    seriesMetadata.setPosterFileNodeId(poster.getId());
                }
                if (fanart != null) {
                    seriesMetadata.setBackdropFileNodeId(fanart.getId());
                }
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
        persistSeasonPosters(series, seriesFolder);
        persistEpisodeNfos(series);
    }

    private void persistSeasonPosters(MediaSeries series, FileNode seriesFolder) {
        List<MediaSeason> seasons = mediaSeasonMapper.selectList(
                new LambdaQueryWrapper<MediaSeason>().eq(MediaSeason::getSeriesId, series.getId()));
        for (MediaSeason season : seasons) {
            if (season.getSeasonNo() == null || season.getMetadataId() == null) {
                continue;
            }
            MediaMetadata seasonMetadata = mediaMetadataMapper.selectById(season.getMetadataId());
            if (seasonMetadata == null) {
                continue;
            }
            if (MediaMetadataSource.LOCAL_NFO.getCode().equals(seasonMetadata.getSource())) {
                // 本地图片来源：季海报为用户提供、已在视频目录，跳过写回
                seasonMetadata.setPersistStatus(MediaPersistStatus.PERSISTED.getCode());
                mediaMetadataMapper.updateById(seasonMetadata);
                continue;
            }
            try {
                FileNode poster = ensureArtwork(seriesFolder, nfoSupport.seasonPosterName(season.getSeasonNo()),
                        seasonMetadata, "poster_path", "poster");
                if (poster != null) {
                    seasonMetadata.setPosterFileNodeId(poster.getId());
                }
                seasonMetadata.setPersistStatus(MediaPersistStatus.PERSISTED.getCode());
            } catch (Exception e) {
                log.warn("季海报写回失败: season={}, error={}", season.getId(), e.getMessage());
                seasonMetadata.setPersistStatus(MediaPersistStatus.FAILED.getCode());
            }
            mediaMetadataMapper.updateById(seasonMetadata);
        }
    }

    private void persistEpisodeNfos(MediaSeries series) {
        List<MediaItem> episodes = mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                .eq(MediaItem::getSeriesId, series.getId())
                .eq(MediaItem::getItemType, MediaItemType.EPISODE.getCode())
                .isNotNull(MediaItem::getMetadataId));
        for (MediaItem episode : episodes) {
            MediaMetadata episodeMetadata = mediaMetadataMapper.selectById(episode.getMetadataId());
            if (episodeMetadata == null || !"episode".equals(episodeMetadata.getMediaType())) {
                continue;
            }
            if (MediaMetadataSource.LOCAL_NFO.getCode().equals(episodeMetadata.getSource())) {
                // 本地 NFO/图片来源：集内容为用户提供、已在视频目录，跳过写回
                episodeMetadata.setPersistStatus(MediaPersistStatus.PERSISTED.getCode());
                mediaMetadataMapper.updateById(episodeMetadata);
                continue;
            }
            try {
                FileNode file = fileMapper.selectById(episode.getFileNodeId());
                if (file == null) {
                    throw new IllegalStateException("集视频文件节点不存在: " + episode.getFileNodeId());
                }
                FileNode dir = fileMapper.selectById(file.getParentId());
                writeNfo(dir, nfoSupport.nfoNameOf(file.getName()), episodeMetadata,
                        episode.getSeasonNo(), episode.getEpisodeNo());
                FileNode thumb = ensureArtwork(dir, nfoSupport.episodeThumbNameOf(file.getName()),
                        episodeMetadata, "still_path", "poster");
                if (thumb != null) {
                    episodeMetadata.setPosterFileNodeId(thumb.getId());
                }
                episodeMetadata.setPersistStatus(MediaPersistStatus.PERSISTED.getCode());
            } catch (Exception e) {
                log.warn("集元数据写回失败: item={}, error={}", episode.getId(), e.getMessage());
                episodeMetadata.setPersistStatus(MediaPersistStatus.FAILED.getCode());
            }
            mediaMetadataMapper.updateById(episodeMetadata);
        }
    }

    /**
     * 生成 NFO 并写入目标目录（覆盖同名）。
     */
    private FileNode writeNfo(FileNode dir, String name, MediaMetadata metadata, Integer seasonNo, Integer episodeNo) {
        String xml = nfoSupport.generate(metadata, seasonNo, episodeNo);
        return writeFileNode(dir, name, xml.getBytes(StandardCharsets.UTF_8), nfoSupport.nfoMimeType());
    }

    /**
     * 确保图片文件存在并返回其节点：local_nfo 来源仅绑定已存在的本地图片；
     * tmdb 来源按元数据 rawJson 中的 TMDB 图片路径下载写入（已存在同名节点则覆盖）。
     */
    private FileNode ensureArtwork(FileNode dir, String name, MediaMetadata metadata, String jsonField, String kind) {
        FileNode existing = findChildFile(dir.getUserId(), dir.getId(), name);
        if (MediaMetadataSource.LOCAL_NFO.getCode().equals(metadata.getSource())) {
            return existing;
        }
        String tmdbPath = extractJsonField(metadata.getRawJson(), jsonField);
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
            userSpaceSupport.updateUsedSpace(user, space, delta);
            existing.setSize((long) content.length);
            return existing;
        }
        FileNode node = fileNodeSupport.buildFileNode(userId, dir.getId(), name, content.length, hash,
                space.getId(), mimeType);
        node.setLastModified(System.currentTimeMillis());
        fileNodeSupport.setNodePath(node, dir.getId());
        fileMapper.insert(node);
        userSpaceSupport.updateUsedSpace(user, space, content.length);
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
}
