package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 媒体库来源目录实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_media_directory_source")
public class MediaDirectorySource extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属媒体库 ID。
     */
    private String directoryId;

    /**
     * 虚拟文件树文件夹节点 ID（本地或远程均可）。
     */
    private String fileNodeId;
}
