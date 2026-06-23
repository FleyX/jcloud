package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.CommonStatus;
import com.fleyx.jcloud.mapper.RoleMapper;
import com.fleyx.jcloud.model.convert.RoleConvert;
import com.fleyx.jcloud.model.po.Role;
import com.fleyx.jcloud.model.vo.RoleVo;
import com.fleyx.jcloud.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 角色业务实现。
 */
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final RoleMapper roleMapper;
    private final RoleConvert roleConvert;

    @Override
    public List<RoleVo> listAll() {
        LambdaQueryWrapper<Role> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Role::getStatus, CommonStatus.ENABLED.getCode());
        wrapper.orderByAsc(Role::getId);
        return roleConvert.poListToVoList(roleMapper.selectList(wrapper));
    }
}
