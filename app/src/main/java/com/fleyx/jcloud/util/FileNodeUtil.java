package com.fleyx.jcloud.util;

import com.fleyx.jcloud.common.constant.FileNodeConstants;

/**
 * 文件节点通用工具类。
 */
public final class FileNodeUtil {

    private FileNodeUtil() {
    }

    /**
     * 归一化父节点 ID。
     * <p>
     * 前端约定根目录使用 {@code "0"} 表示，但数据库存储与后端内部统一使用
     * {@link FileNodeConstants#ROOT_ID}，因此对所有从外部传入的 parentId 进行归一化。
     *
     * @param parentId 原始父节点 ID，可能为 {@code null} 或 {@code "0"}
     * @return 归一化后的父节点 ID，不会返回 {@code null}
     */
    public static String normalizeParentId(String parentId) {
        if (parentId == null || "0".equals(parentId)) {
            return FileNodeConstants.ROOT_ID;
        }
        return parentId;
    }
}
