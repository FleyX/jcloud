package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 上传前预检结果视图。
 * <p>
 * 同时返回目标目录下的同名冲突列表，以及可用于秒传的候选文件列表。
 */
@Data
public class UploadPreCheckVo {

    /**
     * 目标目录下已存在的同名冲突项。
     */
    private List<ConflictItemVo> conflicts;

    /**
     * 与上传文件 hash 匹配的可秒传候选文件。
     */
    private List<FileNodeVo> candidates;
}
