package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.dto.PermissionSaveDto;
import com.fleyx.jcloud.model.dto.PermissionUpdateDto;
import com.fleyx.jcloud.model.po.Resource;
import com.fleyx.jcloud.model.vo.PermissionTreeVo;
import com.fleyx.jcloud.model.vo.PermissionVo;

import java.util.List;

/**
 * 权限业务接口。
 */
public interface PermissionService {

    /**
     * 新增权限。
     *
     * @param dto 权限保存 DTO
     * @return 权限视图
     */
    PermissionVo savePermission(PermissionSaveDto dto);

    /**
     * 更新权限。
     *
     * @param id  权限 ID
     * @param dto 权限更新 DTO
     * @return 权限视图
     */
    PermissionVo updatePermission(String id, PermissionUpdateDto dto);

    /**
     * 根据 ID 查询权限。
     *
     * @param id 权限 ID
     * @return 权限视图
     */
    PermissionVo getById(String id);

    /**
     * 删除权限。
     *
     * @param id 权限 ID
     */
    void removeById(String id);

    /**
     * 修改权限状态。
     *
     * @param id     权限 ID
     * @param status 状态：1 启用，0 禁用
     */
    void updateStatus(String id, Integer status);

    /**
     * 查询权限树。
     *
     * @return 权限树列表
     */
    List<PermissionTreeVo> tree();

    /**
     * 查询资源列表。
     *
     * @return 资源列表
     */
    List<Resource> listResources();
}
