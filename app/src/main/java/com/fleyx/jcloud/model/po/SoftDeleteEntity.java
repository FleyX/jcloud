package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 支持逻辑删除的基础实体类。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SoftDeleteEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 逻辑删除时间戳：0 表示未删除，删除时写入毫秒时间戳。
     */
    @TableLogic(value = "0", delval = "(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000)::bigint")
    @TableField(value = "delete_at", fill = FieldFill.INSERT)
    private Long deleteAt;

}
