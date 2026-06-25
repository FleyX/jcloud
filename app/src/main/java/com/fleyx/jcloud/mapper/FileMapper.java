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

    /**
     * 按名称关键字模糊搜索用户的文件节点（跨文件夹）。
     * <p>
     * 使用 PostgreSQL trigram 相似度匹配，同时保留子串匹配，
     * 结果按文件夹优先、时间倒序排列。
     *
     * @param userId     用户 ID
     * @param keyword    搜索关键字（用于 trigram 相似度）
     * @param likePattern 转义后的关键字（用于 ILIKE 子串匹配）
     * @return 文件节点列表
     */
    List<FileNode> searchByName(@Param("userId") Long userId,
                                @Param("keyword") String keyword,
                                @Param("likePattern") String likePattern);
}
