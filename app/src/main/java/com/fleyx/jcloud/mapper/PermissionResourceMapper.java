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
     * 根据资源编码查询关联的权限编码列表。
     *
     * @param resourceCode 资源编码
     * @return 权限编码列表
     */
    List<String> selectPermissionCodesByResourceCode(@Param("resourceCode") String resourceCode);

    /**
     * 根据角色 ID 列表查询其可访问的资源编码列表。
     *
     * @param roleIds 角色 ID 列表
     * @return 资源编码列表（METHOD:URI）
     */
    List<String> selectResourceCodesByRoleIds(@Param("roleIds") List<Long> roleIds);
}
