package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.PermissionResource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PermissionResourceMapper extends BaseMapper<PermissionResource> {

    List<String> selectPermissionCodesByResourceCode(@Param("resourceCode") String resourceCode);

    List<String> selectResourceCodesByRoleIds(@Param("roleIds") List<Long> roleIds);

    void deleteByPermissionId(@Param("permissionId") Long permissionId);

    void batchInsert(@Param("list") List<PermissionResource> list);

    List<Long> selectResourceIdsByPermissionId(@Param("permissionId") Long permissionId);

    List<String> selectResourceCodesByPermissionIds(@Param("permissionIds") List<Long> permissionIds);
}
