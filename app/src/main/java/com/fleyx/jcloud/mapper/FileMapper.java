package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.FileNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文件节点数据访问层。
 */
@Mapper
public interface FileMapper extends BaseMapper<FileNode> {

    /**
     * 物理删除指定 ID 的节点。
     *
     * @param id 节点 ID
     * @return 影响行数
     */
    int physicalDeleteById(@Param("id") Long id);

    /**
     * 批量物理删除节点。
     *
     * @param ids 节点 ID 列表
     * @return 影响行数
     */
    int physicalDeleteByIds(@Param("ids") List<Long> ids);

    /**
     * 查询指定路径前缀下的所有节点（含自身）。
     *
     * @param userId 用户 ID
     * @param prefix 路径前缀
     * @return 节点列表
     */
    List<FileNode> selectByPathNamePrefix(@Param("userId") Long userId, @Param("prefix") String pathName);

    /**
     * 查询指定路径前缀下的所有文件类型节点。
     *
     * @param userId 用户 ID
     * @param prefix 路径前缀
     * @return 文件节点列表
     */
    List<FileNode> selectFilesByPathNamePrefix(@Param("userId") Long userId, @Param("prefix") String pathName);
}
