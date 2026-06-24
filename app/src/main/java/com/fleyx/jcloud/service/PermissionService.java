package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.dto.PermissionSaveDto;
import com.fleyx.jcloud.model.dto.PermissionUpdateDto;
import com.fleyx.jcloud.model.po.Resource;
import com.fleyx.jcloud.model.vo.PermissionTreeVo;
import com.fleyx.jcloud.model.vo.PermissionVo;

import java.util.List;

public interface PermissionService {

    PermissionVo savePermission(PermissionSaveDto dto);

    PermissionVo updatePermission(Long id, PermissionUpdateDto dto);

    PermissionVo getById(Long id);

    void removeById(Long id);

    void updateStatus(Long id, Integer status);

    List<PermissionTreeVo> tree();

    List<Resource> listResources();
}
