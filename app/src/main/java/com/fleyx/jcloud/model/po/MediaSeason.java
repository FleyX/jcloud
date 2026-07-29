package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 电视剧季实体，由扫描自动维护。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_media_season")
public class MediaSeason extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属剧 ID。
     */
    private String seriesId;

    /**
     * 季号，为空表示未识别季。
     */
    private Integer seasonNo;
}
