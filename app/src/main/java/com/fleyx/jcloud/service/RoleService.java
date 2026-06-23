package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.vo.RoleVo;

import java.util.List;

/**
 * 角色业务接口。
 */
public interface RoleService {

    /**
     * 查询全部有效角色。
     *
     * @return 角色视图列表
     */
    List<RoleVo> listAll();
}
