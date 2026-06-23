package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.model.vo.RoleVo;
import com.fleyx.jcloud.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色控制器。
 * 接口级鉴权由 {@link com.fleyx.jcloud.filter.AuthTokenFilter} 根据用户角色绑定的 t_resource 配置统一处理。
 */
@RestController
@RequestMapping(CommonConstant.API + "/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    /**
     * 查询全部有效角色。
     */
    @GetMapping
    public R<List<RoleVo>> list() {
        return R.ok(roleService.listAll());
    }
}
