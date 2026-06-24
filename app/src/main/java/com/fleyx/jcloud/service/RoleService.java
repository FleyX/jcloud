package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.model.dto.RolePageQueryDto;
import com.fleyx.jcloud.model.dto.RoleSaveDto;
import com.fleyx.jcloud.model.dto.RoleUpdateDto;
import com.fleyx.jcloud.model.vo.RoleVo;

import java.util.List;

/**
 * 角色业务接口。
 */
public interface RoleService {

    /**
     * 新增角色。
     *
     * @param dto 角色保存 DTO
     * @return 角色视图
     */
    RoleVo saveRole(RoleSaveDto dto);

    /**
     * 更新角色。
     *
     * @param id  角色 ID
     * @param dto 角色更新 DTO
     * @return 角色视图
     */
    RoleVo updateRole(Long id, RoleUpdateDto dto);

    /**
     * 根据 ID 查询角色。
     *
     * @param id 角色 ID
     * @return 角色视图
     */
    RoleVo getById(Long id);

    /**
     * 分页查询角色。
     *
     * @param dto 分页查询条件
     * @return 分页结果
     */
    IPage<RoleVo> pageRoles(RolePageQueryDto dto);

    /**
     * 查询所有启用的角色。
     *
     * @return 角色视图列表
     */
    List<RoleVo> listAllEnabled();

    /**
     * 删除角色。
     *
     * @param id 角色 ID
     */
    void removeById(Long id);

    /**
     * 修改角色状态。
     *
     * @param id     角色 ID
     * @param status 状态：1 启用，0 禁用
     */
    void updateStatus(Long id, Integer status);
}
