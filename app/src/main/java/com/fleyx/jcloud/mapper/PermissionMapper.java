package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.Permission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 权限资源数据访问层。
 */
@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {

    /**
     * 根据用户 ID 查询其全部有效权限编码。
     *
     * @param userId 用户 ID
     * @return 权限编码列表
     */
    List<String> selectCodesByUserId(@Param("userId") Long userId);

    /**
     * 根据角色 ID 列表查询权限编码。
     *
     * @param roleIds 角色 ID 列表
     * @return 权限编码列表
     */
    List<String> selectCodesByRoleIds(@Param("roleIds") List<Long> roleIds);
}
