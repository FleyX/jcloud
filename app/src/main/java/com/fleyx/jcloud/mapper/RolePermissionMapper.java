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
    void deleteByRoleId(@Param("roleId") String roleId);

    /**
     * 批量插入角色权限关联。
     *
     * @param list 关联列表
     * @return 插入条数
     */
    int batchInsert(@Param("list") List<RolePermission> list);

    /**
     * 根据角色 ID 查询权限编码列表。
     *
     * @param roleId 角色 ID
     * @return 权限编码列表
     */
    List<String> selectPermissionCodesByRoleId(@Param("roleId") String roleId);

    /**
     * 根据角色 ID 列表查询权限编码列表。
     *
     * @param roleIds 角色 ID 列表
     * @return 权限编码列表
     */
    List<String> selectPermissionCodesByRoleIds(@Param("roleIds") List<String> roleIds);

    /**
     * 查询所有不重复的权限编码（用于启动时审计悬空绑定）。
     *
     * @return 权限编码列表
     */
    List<String> selectDistinctPermissionCodes();
}
