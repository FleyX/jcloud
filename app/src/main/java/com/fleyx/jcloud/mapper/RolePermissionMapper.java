package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.RolePermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 角色权限关联数据访问层。
 */
@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermission> {

    /**
     * 根据角色 ID 删除角色权限关联。
     *
     * @param roleId 角色 ID
     */
    void deleteByRoleId(@Param("roleId") Long roleId);

    /**
     * 批量插入角色权限关联。
     *
     * @param list 关联列表
     * @return 插入条数
     */
    int batchInsert(@Param("list") List<RolePermission> list);

    /**
     * 根据角色 ID 查询权限 ID 列表。
     *
     * @param roleId 角色 ID
     * @return 权限 ID 列表
     */
    List<Long> selectPermissionIdsByRoleId(@Param("roleId") Long roleId);
}
