package com.fleyx.jcloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fleyx.jcloud.model.po.Role;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色数据访问层。
 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {
}
