package com.fleyx.jcloud.util;

import com.fleyx.jcloud.common.enums.BatchUploadErrorCode;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.model.vo.BatchChunkedUploadInitItemVo;
import com.fleyx.jcloud.model.vo.BatchUploadPreCheckItemVo;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

/**
 * 批量上传公共工具。
 * <p>
 * 封装批量接口中 item 级校验、错误填充等通用逻辑。
 */
public final class BatchUploadHelper {

    private BatchUploadHelper() {
    }

    /**
     * 校验批量请求项非空且不超过最大限制。
     */
    public static void validateBatchItems(List<?> items, int maxSize, String itemName) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "items 不能为空");
        }
        if (items.size() > maxSize) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "单次批量" + itemName + "不能超过 " + maxSize + " 个文件");
        }
    }

    /**
     * 校验 clientFileId 必填且在当前 batch 内唯一。
     *
     * @return 若校验通过返回 {@code null}，否则返回对应错误码
     */
    public static BatchUploadErrorCode validateClientFileId(String clientFileId, Set<String> clientFileIds) {
        if (!StringUtils.hasText(clientFileId)) {
            return BatchUploadErrorCode.MISSING_CLIENT_FILE_ID;
        }
        if (!clientFileIds.add(clientFileId)) {
            return BatchUploadErrorCode.DUPLICATE_CLIENT_FILE_ID;
        }
        return null;
    }

    /**
     * 构建目标路径唯一键，用于检测 batch 内重复文件。
     */
    public static String buildPathKey(String parentId, String relativePath, String fileName) {
        String normalizedParentId = FileNodeUtil.normalizeParentId(parentId);
        if (StringUtils.hasText(relativePath)) {
            return normalizedParentId + "/" + relativePath;
        }
        return normalizedParentId + "/" + fileName;
    }

    /**
     * 填充预检响应项错误信息。
     */
    public static void fillError(BatchUploadPreCheckItemVo result, BatchUploadErrorCode errorCode) {
        result.setStatus("error");
        result.setErrorCode(errorCode.name());
        result.setErrorMessage(errorCode.getMessage());
    }

    /**
     * 填充预检响应项错误信息。
     */
    public static void fillError(BatchUploadPreCheckItemVo result, BatchUploadErrorCode errorCode, String message) {
        result.setStatus("error");
        result.setErrorCode(errorCode.name());
        result.setErrorMessage(message);
    }

    /**
     * 填充分片初始化响应项错误信息。
     */
    public static void fillError(BatchChunkedUploadInitItemVo result, BatchUploadErrorCode errorCode) {
        result.setStatus("error");
        result.setErrorCode(errorCode.name());
        result.setErrorMessage(errorCode.getMessage());
    }

    /**
     * 填充分片初始化响应项错误信息。
     */
    public static void fillError(BatchChunkedUploadInitItemVo result, BatchUploadErrorCode errorCode, String message) {
        result.setStatus("error");
        result.setErrorCode(errorCode.name());
        result.setErrorMessage(message);
    }
}
