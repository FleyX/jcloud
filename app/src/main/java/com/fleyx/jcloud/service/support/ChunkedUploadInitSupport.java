package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.BatchUploadErrorCode;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.config.UploadProperties;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.dto.ChunkedUploadInitDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.BatchChunkedUploadInitItemVo;
import com.fleyx.jcloud.model.vo.ChunkedUploadInitVo;
import com.fleyx.jcloud.service.FolderPathService;
import com.fleyx.jcloud.util.BatchUploadHelper;
import com.fleyx.jcloud.util.FileNodeUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 分片上传初始化支撑组件。
 */
@Component
@RequiredArgsConstructor
public class ChunkedUploadInitSupport {

    private final FileMapper fileMapper;
    private final FolderPathService folderPathService;
    private final UploadProperties uploadProperties;
    private final ChunkedUploadChunkSupport chunkedUploadChunkSupport;

    /**
     * 批量初始化分片上传任务，单项失败不影响其他项。
     *
     * @param items  初始化参数列表
     * @param userId 用户 ID
     * @param user   用户
     * @param space  存储空间
     * @return 批量初始化结果项列表
     */
    public List<BatchChunkedUploadInitItemVo> doBatchInit(List<ChunkedUploadInitDto> items, String userId,
                                                          User user, StorageSpace space) {
        BatchUploadHelper.validateBatchItems(items, 150, "初始化");

        List<BatchChunkedUploadInitItemVo> results = new ArrayList<>(items.size());
        Set<String> clientFileIds = new HashSet<>();
        Set<String> processedPaths = new HashSet<>();

        for (ChunkedUploadInitDto item : items) {
            String clientFileId = item.getClientFileId();
            BatchChunkedUploadInitItemVo result = new BatchChunkedUploadInitItemVo();
            result.setClientFileId(clientFileId);

            BatchUploadErrorCode clientIdError = BatchUploadHelper.validateClientFileId(clientFileId, clientFileIds);
            if (clientIdError != null) {
                BatchUploadHelper.fillError(result, clientIdError);
                results.add(result);
                continue;
            }

            try {
                validateInitItem(item);
                String pathKey = BatchUploadHelper.buildPathKey(item.getParentId(), item.getRelativePath(), item.getFileName());
                if (!processedPaths.add(pathKey)) {
                    BatchUploadHelper.fillError(result, BatchUploadErrorCode.DUPLICATE_FILE_IN_BATCH);
                    results.add(result);
                    continue;
                }

                ChunkedUploadInitVo data = doInit(item, userId, user, space);
                result.setStatus("success");
                result.setData(data);
            } catch (BusinessException e) {
                BatchUploadHelper.fillError(result, BatchUploadHelper.mapErrorCode(e), e.getMessage());
            } catch (Exception e) {
                BatchUploadHelper.fillError(result, BatchUploadErrorCode.SYSTEM_ERROR, e.getMessage());
            }
            results.add(result);
        }
        return results;
    }

    private ChunkedUploadInitVo doInit(ChunkedUploadInitDto dto, String userId, User user, StorageSpace space) {
        String finalParentId = FileNodeUtil.normalizeParentId(dto.getParentId());
        String finalFileName = dto.getFileName();
        FileNode parentNode = fileMapper.selectById(finalParentId);
        boolean remoteParent = parentNode != null && FileNodeConstants.SOURCE_REMOTE.equals(parentNode.getSourceType());
        if (remoteParent) {
            if (StringUtils.hasText(dto.getRelativePath())) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "远程目录暂不支持文件夹上传");
            }
        } else if (StringUtils.hasText(dto.getRelativePath())) {
            finalParentId = folderPathService.resolveOrCreateFolderPath(userId, dto.getParentId(), dto.getRelativePath());
            finalFileName = FilePathUtil.extractFileName(dto.getRelativePath());
        }

        String uploadId = generateUploadId();
        Path tempDir = FilePathUtil.resolveUploadTempDir(space, user.getUsername(), uploadId);
        try {
            Files.createDirectories(tempDir);
            chunkedUploadChunkSupport.saveUploadMeta(tempDir, dto, finalParentId, finalFileName);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "创建上传临时目录失败");
        }

        long chunkSize = uploadProperties.getChunkSize();
        long fileSize = dto.getSize();
        int totalChunks = (int) ((fileSize + chunkSize - 1) / chunkSize);
        ChunkedUploadInitVo vo = new ChunkedUploadInitVo();
        vo.setUploadId(uploadId);
        vo.setChunkSize((int) chunkSize);
        vo.setTotalChunks(totalChunks);
        return vo;
    }

    private void validateInitItem(ChunkedUploadInitDto dto) {
        if (!StringUtils.hasText(dto.getFileName())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "文件名不能为空");
        }
        if (dto.getSize() == null || dto.getSize() <= 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "文件大小必须大于 0");
        }
    }

    private String generateUploadId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
