package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.Permission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {

    List<String> selectCodesByUserId(@Param("userId") Long userId);

    List<String> selectCodesByRoleIds(@Param("roleIds") List<Long> roleIds);

    /**
     * 查询指定权限及其所有祖先权限 ID。
     */
    Set<Long> selectAncestorIds(@Param("permissionId") Long permissionId);

    /**
     * 查询指定权限及其所有后代权限 ID。
     */
    Set<Long> selectDescendantIds(@Param("permissionId") Long permissionId);

    /**
     * 查询所有有效权限（用于构建树）。
     */
    List<Permission> selectAllValid();

    /**
     * 根据角色 ID 列表查询有效权限 ID。
     */
    List<Long> selectIdsByRoleIds(@Param("roleIds") List<Long> roleIds);

    /**
     * 根据权限 ID 列表查询权限编码。
     */
    List<String> selectCodesByIds(@Param("ids") List<Long> ids);
}
