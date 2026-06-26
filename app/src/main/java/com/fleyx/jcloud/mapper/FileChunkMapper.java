package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.FileChunk;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件分片上传记录数据访问层。
 */
@Mapper
public interface FileChunkMapper extends BaseMapper<FileChunk> {
}
