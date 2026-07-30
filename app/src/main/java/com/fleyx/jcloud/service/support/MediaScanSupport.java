package com.fleyx.jcloud.service.support;

import cn.hutool.crypto.digest.DigestUtil;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.MediaFileNameParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 媒体库扫描辅助组件：ffprobe 探测填充、文件变更哈希、电视三层结构解析等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaScanSupport {

    private final FileMapper fileMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final RemoteFileService remoteFileService;
    private final MediaProbeSupport mediaProbeSupport;

    /**
     * ffprobe 探测并填充条目的时长/编码/分辨率，失败仅记日志。
     */
    public void fillProbeResult(MediaItem item, FileNode file, String username, Map<String, String> idToName) {
        try {
            MediaProbeResult probe = probeFile(file, username, idToName);
            item.setDurationMs(probe.durationMs());
            item.setContainer(probe.container());
            item.setVideoCodec(probe.videoCodec());
            item.setAudioCodec(probe.audioCodec());
            item.setWidth(probe.width());
            item.setHeight(probe.height());
        } catch (Exception e) {
            log.warn("ffprobe 探测失败: {}, {}", file.getName(), e.getMessage());
        }
    }

    private MediaProbeResult probeFile(FileNode file, String username, Map<String, String> idToName) {
        if (FileNodeConstants.SOURCE_REMOTE.equals(file.getSourceType())) {
            try (InputStream in = remoteFileService.download(file, file.getUserId()).getInputStream()) {
                return mediaProbeSupport.probe(in);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("远程文件读取失败", e);
            }
        }
        StorageSpace space = storageSpaceMapper.selectById(file.getStorageSpaceId());
        Path physicalPath = FilePathUtil.resolvePhysicalPath(file, FilePathUtil.contextOf(space, username, idToName));
        return mediaProbeSupport.probe(physicalPath);
    }

    /**
     * 计算文件变更哈希：相对路径（相对视频目录的名称路径）+ 文件名 + 文件大小。
     * 文件名、移动（含祖先目录改名）、大小任一变化都会改变哈希。
     *
     * @param file             文件节点
     * @param folderFullIdPath 视频目录的完整物化路径
     * @param idToName         节点 ID → 名称缓存
     * @return MD5 哈希
     */
    public String computeFileHash(FileNode file, String folderFullIdPath, Map<String, String> idToName) {
        StringBuilder relative = new StringBuilder();
        for (String folderId : relativeFolderIds(file, folderFullIdPath)) {
            String name = idToName.get(folderId);
            if (name != null) {
                relative.append(name).append('/');
            }
        }
        relative.append(file.getName()).append(':').append(file.getSize());
        return DigestUtil.md5Hex(relative.toString());
    }

    /**
     * 电视三层结构归属结果。
     *
     * @param seriesName  剧名（一级子文件夹名清洗结果）
     * @param seasonNo    季号（二级子文件夹名解析，无法解析或无季文件夹归第一季）
     * @param releaseYear 首播年份（一级子文件夹名解析，未解析出为 null）
     */
    public record TvLocation(String seriesName, Integer seasonNo, Integer releaseYear) {
    }

    /**
     * 按固定三层结构（视频目录/剧/季/集）解析剧集归属。
     * <p>
     * 根下散文件（深度 1）与超过三层的文件返回 null 表示忽略；
     * 剧文件夹下散文件（深度 2）归第一季。
     *
     * @param file             文件节点
     * @param folderFullIdPath 视频目录的完整物化路径
     * @param idToName         节点 ID → 名称缓存
     * @return 归属结果，忽略返回 null
     */
    public TvLocation resolveTvLocation(FileNode file, String folderFullIdPath, Map<String, String> idToName) {
        List<String> folderIds = relativeFolderIds(file, folderFullIdPath);
        if (folderIds.size() == 1) {
            String folderName = idToName.get(folderIds.getFirst());
            String seriesName = MediaFileNameParser.cleanTitle(folderName);
            return seriesName.isBlank() ? null
                    : new TvLocation(seriesName, 1, MediaFileNameParser.parseYear(folderName));
        }
        if (folderIds.size() == 2) {
            String folderName = idToName.get(folderIds.getFirst());
            String seriesName = MediaFileNameParser.cleanTitle(folderName);
            if (seriesName.isBlank()) {
                return null;
            }
            Integer seasonNo = MediaFileNameParser.parseSeasonNo(idToName.get(folderIds.get(1)));
            return new TvLocation(seriesName, seasonNo == null ? 1 : seasonNo,
                    MediaFileNameParser.parseYear(folderName));
        }
        return null;
    }

    /**
     * 文件相对视频目录的祖先文件夹 ID 列表（不含视频目录本身与文件自身）。
     */
    private List<String> relativeFolderIds(FileNode file, String folderFullIdPath) {
        List<String> result = new ArrayList<>();
        String path = file.getPath() == null ? "" : file.getPath();
        String prefix = folderFullIdPath + FileNodeConstants.PATH_SEPARATOR;
        if (!path.startsWith(prefix)) {
            return result;
        }
        for (String id : path.substring(prefix.length()).split("\\" + FileNodeConstants.PATH_SEPARATOR)) {
            if (!id.isBlank()) {
                result.add(id);
            }
        }
        return result;
    }

    /**
     * 补充视频目录祖先节点的名称缓存。
     */
    public void fillAncestorNames(FileNode folder, String userId, Map<String, String> idToName) {
        Set<String> ancestorIds = new java.util.HashSet<>();
        if (folder.getPath() != null) {
            for (String id : folder.getPath().split("\\.")) {
                if (!FileNodeConstants.ROOT_ID.equals(id)) {
                    ancestorIds.add(id);
                }
            }
        }
        ancestorIds.removeAll(idToName.keySet());
        if (!ancestorIds.isEmpty()) {
            for (FileNode ancestor : fileMapper.selectBatchIds(ancestorIds)) {
                if (userId.equals(ancestor.getUserId())) {
                    idToName.put(ancestor.getId(), ancestor.getName());
                }
            }
        }
    }

    /**
     * 判断文件与已扫描条目相比是否未变化（文件变更哈希）。
     */
    public boolean unchanged(MediaItem item, String fileHash) {
        return Objects.equals(item.getFileHash(), fileHash);
    }
}
