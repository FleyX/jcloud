package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.UserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户角色关联数据访问层。
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {

    /**
     * 根据用户 ID 查询角色 ID 列表。
     *
     * @param userId 用户 ID
     * @return 角色 ID 列表
     */
    List<Long> selectRoleIdsByUserId(@Param("userId") Long userId);

    /**
     * 批量插入用户角色关联。
     *
     * @param userRoles 关联列表
     * @return 插入条数
     */
    int batchInsert(@Param("list") List<UserRole> userRoles);

    /**
     * 根据用户 ID 列表查询关联记录。
     *
     * @param userIds 用户 ID 列表
     * @return 用户角色关联列表
     */
    List<UserRole> selectByUserIds(@Param("userIds") List<Long> userIds);

    /**
     * 根据角色 ID 查询用户 ID 列表。
     *
     * @param roleId 角色 ID
     * @return 用户 ID 列表
     */
    List<Long> selectUserIdsByRoleId(@Param("roleId") Long roleId);
}
