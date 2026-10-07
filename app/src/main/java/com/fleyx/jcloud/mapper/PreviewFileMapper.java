package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.PreviewFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;

/**
 * 预览文件数据访问层。
 */
@Mapper
public interface PreviewFileMapper extends BaseMapper<PreviewFile> {

    /**
     * 批量物理删除预览文件行（绕过 {@code @TableLogic} 逻辑删除）。
     *
     * @param ids 预览文件 ID 列表
     * @return 影响行数
     */
    int physicalDeleteByIds(@Param("ids") Collection<String> ids);
}
