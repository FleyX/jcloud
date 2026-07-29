package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 电视剧实体，按用户+剧名唯一，由扫描自动维护。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_media_series")
public class MediaSeries extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属用户 ID。
     */
    private String userId;

    /**
     * 剧名。
     */
    private String seriesName;

    /**
     * 匹配到的 TMDB 元数据 ID。
     */
    private String metadataId;

    /**
     * 匹配状态：matched / manual / unmatched。
     */
    private String matchStatus;

    /**
     * 剧内最早一集的文件修改时间（毫秒），用于添加时间排序。
     */
    private Long minFileLastModified;
}
