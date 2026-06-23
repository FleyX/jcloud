package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.RolePermission;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色权限关联数据访问层。
 */
@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermission> {
}
