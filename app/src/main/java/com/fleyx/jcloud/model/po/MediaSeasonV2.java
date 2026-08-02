package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 电视剧季实体（文件夹锚定）。
 * expand 阶段并行新表 t_media_season_v2，contract 阶段（issue #21）rename 为 t_media_season。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_media_season_v2")
public class MediaSeasonV2 extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属剧 ID。
     */
    private String seriesId;

    /**
     * 锚：季文件夹的虚拟文件树节点 ID（唯一）。
     */
    private String folderNodeId;

    /**
     * 季号，为空表示未识别季。
     */
    private Integer seasonNo;

    /**
     * 季元数据 ID，未匹配时为空。
     */
    private String metadataId;
}
