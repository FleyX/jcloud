package com.fleyx.jcloud.model.convert;

import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import org.mapstruct.Mapper;

/**
 * 文件节点对象转换器。
 */
@Mapper(componentModel = "spring")
public interface FileConvert {

    /**
     * PO 转 VO。
     *
     * @param po 文件节点实体
     * @return 文件节点视图
     */
    FileNodeVo poToVo(FileNode po);
}
