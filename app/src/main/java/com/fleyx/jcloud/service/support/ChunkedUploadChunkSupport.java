package com.fleyx.jcloud.service.support;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.config.UploadProperties;
import com.fleyx.jcloud.mapper.FileChunkMapper;
import com.fleyx.jcloud.model.dto.ChunkedUploadInitDto;
import com.fleyx.jcloud.model.po.FileChunk;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.ChunkedUploadChunkVo;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * 分片上传分片与上传会话支撑组件。
 */
@Component
@RequiredArgsConstructor
public class ChunkedUploadChunkSupport {

    private static final String META_FILE_NAME = ".upload";

    private final FileChunkMapper fileChunkMapper;
    private final UserSpaceSupport userSpaceSupport;
    private final UploadProperties uploadProperties;

    /**
     * 上传单个分片；当客户端携带分片 hash 时进行校验，为空则跳过。
     *
     * @param userId     用户 ID
     * @param uploadId   上传任务 ID
     * @param chunkIndex 分片索引
     * @param chunk      分片文件
     * @param chunkHash  分片 hash，可为空（局域网场景信任 TCP 完整性，跳过校验）
     * @return 分片上传结果
     */
    public ChunkedUploadChunkVo uploadChunk(String userId, String uploadId, Integer chunkIndex,
                                            MultipartFile chunk, String chunkHash) {
        UploadContext context = loadUploadContext(userId, uploadId);
        if (chunkIndex == null || chunkIndex < 0 || chunkIndex >= context.totalChunks()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "分片索引超出范围");
        }

        if (StringUtils.hasText(chunkHash)) {
            String actualHash;
            try (InputStream is = chunk.getInputStream()) {
                actualHash = DigestUtil.md5Hex(is);
            } catch (IOException e) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "读取分片失败");
            }
            if (!actualHash.equals(chunkHash)) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "分片 hash 校验失败");
            }
        }

        Path chunkPath = context.tempDir().resolve(chunkFileName(chunkIndex));
        try {
            Files.copy(chunk.getInputStream(), chunkPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "保存分片失败");
        }

        // chunk_hash 列非空，客户端未携带 hash 时落库为空字符串
        saveOrUpdateChunkRecord(uploadId, userId, chunkIndex,
                StringUtils.hasText(chunkHash) ? chunkHash : "", chunk.getSize());

        ChunkedUploadChunkVo vo = new ChunkedUploadChunkVo();
        vo.setChunkIndex(chunkIndex);
        vo.setStatus("success");
        return vo;
    }

    /**
     * 查询已上传的分片索引列表。
     *
     * @param userId   用户 ID
     * @param uploadId 上传任务 ID
     * @return 已上传分片索引列表
     */
    public List<Integer> listUploadedChunks(String userId, String uploadId) {
        loadUploadContext(userId, uploadId);
        LambdaQueryWrapper<FileChunk> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileChunk::getUploadId, uploadId);
        wrapper.eq(FileChunk::getUserId, userId);
        wrapper.orderByAsc(FileChunk::getChunkIndex);
        return fileChunkMapper.selectList(wrapper).stream()
                .map(FileChunk::getChunkIndex)
                .toList();
    }

    /**
     * 加载上传任务上下文（用户、存储空间、临时目录与元数据）。
     *
     * @param userId   用户 ID
     * @param uploadId 上传任务 ID
     * @return 上传任务上下文
     */
    public UploadContext loadUploadContext(String userId, String uploadId) {
        User user = userSpaceSupport.requireUser(userId);
        StorageSpace space = userSpaceSupport.requireSpace(user.getStorageSpaceId());
        Path tempDir = FilePathUtil.resolveUploadTempDir(space, user.getUsername(), uploadId);
        if (!Files.exists(tempDir)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "上传任务不存在");
        }
        Properties meta = readUploadMeta(tempDir);
        String fileName = meta.getProperty("fileName");
        long size = Long.parseLong(meta.getProperty("size"));
        String parentId = meta.getProperty("parentId", FileNodeConstants.ROOT_ID);
        long chunkSize = uploadProperties.getChunkSize();
        int totalChunks = (int) ((size + chunkSize - 1) / chunkSize);
        return new UploadContext(user, space, tempDir, fileName, size, parentId, totalChunks);
    }

    /**
     * 写入上传任务元数据。
     *
     * @param tempDir       上传临时目录
     * @param dto           初始化参数
     * @param finalParentId 最终父节点 ID
     * @param finalFileName 最终文件名
     */
    public void saveUploadMeta(Path tempDir, ChunkedUploadInitDto dto,
                               String finalParentId, String finalFileName) throws IOException {
        Properties props = new Properties();
        props.setProperty("fileName", finalFileName);
        props.setProperty("size", String.valueOf(dto.getSize()));
        props.setProperty("parentId", finalParentId);
        try (OutputStream out = Files.newOutputStream(tempDir.resolve(META_FILE_NAME))) {
            props.store(out, "chunked upload metadata");
        }
    }

    private Properties readUploadMeta(Path tempDir) {
        Path metaPath = tempDir.resolve(META_FILE_NAME);
        if (!Files.exists(metaPath)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "上传任务元数据丢失");
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(metaPath)) {
            props.load(in);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "读取上传任务元数据失败");
        }
        return props;
    }

    private void saveOrUpdateChunkRecord(String uploadId, String userId, Integer chunkIndex,
                                         String chunkHash, long size) {
        LambdaQueryWrapper<FileChunk> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileChunk::getUploadId, uploadId)
                .eq(FileChunk::getUserId, userId)
                .eq(FileChunk::getChunkIndex, chunkIndex);
        FileChunk existing = fileChunkMapper.selectOne(wrapper);
        if (existing != null) {
            existing.setChunkHash(chunkHash);
            existing.setSize(size);
            existing.setStatus(1);
            fileChunkMapper.updateById(existing);
            return;
        }

        FileChunk record = new FileChunk();
        record.setUploadId(uploadId);
        record.setUserId(userId);
        record.setChunkIndex(chunkIndex);
        record.setChunkHash(chunkHash);
        record.setSize(size);
        record.setStatus(1);
        fileChunkMapper.insert(record);
    }

    private String chunkFileName(int chunkIndex) {
        return "chunk-" + chunkIndex;
    }

    /**
     * 上传任务上下文。
     */
    public record UploadContext(User user, StorageSpace space, Path tempDir,
                                String fileName, long size, String parentId, int totalChunks) {
    }
}
