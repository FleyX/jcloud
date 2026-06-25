package com.fleyx.jcloud.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 恢复前冲突预检参数。
 */
@Data
public class FilePreCheckRestoreDto {

    /**
     * 要恢复的回收站记录 ID 列表。
     */
    private List<Long> ids;
}
