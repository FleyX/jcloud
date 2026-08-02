package com.fleyx.jcloud.service.support;

import cn.hutool.crypto.digest.DigestUtil;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 媒体库扫描辅助组件（issue #21 起仅保留新模型共用原语）：ffprobe 探测、文件变更哈希、
 * 来源目录相对路径解析与祖先名称缓存。旧模型三层结构解析与条目归属逻辑已随旧表弃用删除。
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
     * ffprobe 探测单个文件（本地物理路径或远程输入流），供电视/电影/其他新模型扫描复用。
     */
    public MediaProbeResult probeFile(FileNode file, String username, Map<String, String> idToName) {
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
     * 计算文件变更哈希：来源目录 ID + 相对路径（相对来源目录的名称路径）+ 文件名 + 文件大小。
     * 文件名、移动（含祖先目录改名）、大小任一变化都会改变哈希；来源目录 ID 避免多来源下同路径文件撞键。
     *
     * @param file             文件节点
     * @param sourceId         来源目录 ID
     * @param folderFullIdPath 来源目录文件夹的完整物化路径
     * @param idToName         节点 ID → 名称缓存
     * @return MD5 哈希
     */
    public String computeFileHash(FileNode file, String sourceId, String folderFullIdPath, Map<String, String> idToName) {
        StringBuilder relative = new StringBuilder(sourceId).append(':');
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
     * 文件相对来源目录的祖先文件夹 ID 列表（不含来源目录本身与文件自身）。
     * 电视/电影新模型扫描按该列表长度判定目录层级结构。
     */
    public List<String> relativeFolderIds(FileNode file, String folderFullIdPath) {
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
     * 补充来源目录文件夹祖先节点的名称缓存。
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
}
