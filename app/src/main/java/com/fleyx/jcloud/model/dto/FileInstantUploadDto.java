package com.fleyx.jcloud.model.dto;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import lombok.Data;

/**
 * 秒传请求参数。
 */
@Data
public class FileInstantUploadDto {

    /**
     * 候选文件节点 ID。
     */
    private String candidateId;

    /**
     * 完整文件 hash。
     */
    private String fullHash;

    /**
     * 新文件名。
     */
    private String fileName;

    /**
     * 目标父节点 ID，默认为根目录 {@link FileNodeConstants#ROOT_ID}。
     */
    private String parentId;

    /**
     * 冲突解决策略：skip / overwrite / keep。
     * <p>
     * 当目标位置已存在同名节点时生效；若未指定且存在冲突则报错。
     */
    private String strategy;
}
