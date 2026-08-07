package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.MediaMetadataSource;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.po.FileNode;
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
 * 媒体元数据写回支撑组件（ADR 0020 / issue #20 起仅保留新模型复用原语）：
 * 削刮成功后把 NFO 与图片写回视频所在目录。
 * <p>
 * 写回为正式 FileNode（本地来源写物理文件 + 插入/覆盖 FileNode，计入用户已用空间）；
 * 远程来源经远程上传通道落盘。已存在同名 FileNode 则覆盖更新，不触发用户冲突流程。
 * 任一步失败仅将对应元数据标记 persist_status=failed，不影响削刮主流程。
 * TMDB 来源图片从 TMDB 下载（w500/w1280）；local_nfo 来源的内容为用户提供、已在视频目录，
 * 跳过该级 nfo/图片写回（ADR 0020 完全信任本地）。
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
