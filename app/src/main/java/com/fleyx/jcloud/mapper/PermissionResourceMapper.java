package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.PermissionResource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 权限资源关联数据访问层。
 */
@Mapper
public interface PermissionResourceMapper extends BaseMapper<PermissionResource> {

    /**
     * 根据资源编码查询权限编码列表。
     *
     * @param resourceCode 资源编码
     * @return 权限编码列表
     */
    List<String> selectPermissionCodesByResourceCode(@Param("resourceCode") String resourceCode);

    /**
     * 根据角色 ID 列表查询资源编码列表。
     *
     * @param roleIds 角色 ID 列表
     * @return 资源编码列表
     */
    List<String> selectResourceCodesByRoleIds(@Param("roleIds") List<String> roleIds);

    /**
     * 根据权限 ID 删除权限资源关联。
     *
     * @param permissionId 权限 ID
     */
    void deleteByPermissionId(@Param("permissionId") String permissionId);

    /**
     * 批量插入权限资源关联。
     *
     * @param list 关联列表
     * @return 插入条数
     */
    int batchInsert(@Param("list") List<PermissionResource> list);

    /**
     * 根据权限 ID 查询资源 ID 列表。
     *
     * @param permissionId 权限 ID
     * @return 资源 ID 列表
     */
    List<String> selectResourceIdsByPermissionId(@Param("permissionId") String permissionId);

    /**
     * 根据权限 ID 列表查询资源编码列表。
     *
     * @param permissionIds 权限 ID 列表
     * @return 资源编码列表
     */
    List<String> selectResourceCodesByPermissionIds(@Param("permissionIds") List<String> permissionIds);
}
