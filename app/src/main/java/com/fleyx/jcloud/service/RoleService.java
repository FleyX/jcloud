package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.model.dto.RolePageQueryDto;
import com.fleyx.jcloud.model.dto.RoleSaveDto;
import com.fleyx.jcloud.model.dto.RoleUpdateDto;
import com.fleyx.jcloud.model.vo.RoleVo;

public interface RoleService {

    RoleVo saveRole(RoleSaveDto dto);

    RoleVo updateRole(Long id, RoleUpdateDto dto);

    RoleVo getById(Long id);

    IPage<RoleVo> pageRoles(RolePageQueryDto dto);

    void removeById(Long id);

    void updateStatus(Long id, Integer status);
}
