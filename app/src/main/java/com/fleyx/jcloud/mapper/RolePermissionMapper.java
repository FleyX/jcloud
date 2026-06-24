package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.RolePermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermission> {

    void deleteByRoleId(@Param("roleId") Long roleId);

    void batchInsert(@Param("list") List<RolePermission> list);

    List<Long> selectPermissionIdsByRoleId(@Param("roleId") Long roleId);
}
