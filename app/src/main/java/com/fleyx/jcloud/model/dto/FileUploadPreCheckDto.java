package com.fleyx.jcloud.model.dto;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import lombok.Data;

/**
 * 上传前冲突预检请求参数。
 */
@Data
public class FileUploadPreCheckDto {

    /**
     * 前端生成的文件唯一标识，用于批量请求中关联请求项与响应项。
     */
    private String clientFileId;

    /**
     * 待上传文件名。
     */
    private String fileName;

    /**
     * 文件大小（字节）。
     */
    private Long size;

    /**
     * 目标父节点 ID，默认为根目录 {@link FileNodeConstants#ROOT_ID}。
     */
    private String parentId;

    /**
     * 相对路径（用于文件夹上传），包含文件名，例如 {@code project/src/main.java}。
     * <p>
     * 目录部分相对于 {@code parentId} 表示文件夹层级，后端会自动创建缺失的文件夹。
     */
    private String relativePath;

    /**
     * 文件身份 hash（小于 150MB 为完整 hash，大于等于 150MB 为采样 hash）。
     * <p>
     * 用于秒传候选文件查询；为空时只进行命名冲突预检。
     */
    private String partialHash;
}
