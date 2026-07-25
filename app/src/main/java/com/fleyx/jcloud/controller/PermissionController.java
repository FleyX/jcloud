package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.permission.PermissionRegistry;
import com.fleyx.jcloud.model.vo.PermissionTreeVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限只读控制器。
 * <p>
 * 权限数据由 permissions.yml 维护并加载到内存，仅提供权限树查询（供角色分配界面使用），
 * 不提供任何写接口。
 */
@RestController
@RequestMapping(CommonConstant.API + "/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionRegistry permissionRegistry;

    /**
     * 查询权限树（只读）。
     */
    @GetMapping("/tree")
    public R<List<PermissionTreeVo>> tree() {
        return R.ok(permissionRegistry.tree());
    }
}
