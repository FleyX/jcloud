package com.fleyx.jcloud.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.model.dto.RolePageQueryDto;
import com.fleyx.jcloud.model.dto.RoleSaveDto;
import com.fleyx.jcloud.model.dto.RoleStatusDto;
import com.fleyx.jcloud.model.dto.RoleUpdateDto;
import com.fleyx.jcloud.model.vo.RoleVo;
import com.fleyx.jcloud.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色管理控制器。
 */
@RestController
@RequestMapping(CommonConstant.API + "/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    /**
     * 查询所有启用的角色。
     */
    @GetMapping
    public R<List<RoleVo>> listAll() {
        return R.ok(roleService.listAllEnabled());
    }

    /**
     * 分页查询角色。
     */
    @GetMapping("/page")
    public R<IPage<RoleVo>> page(RolePageQueryDto dto) {
        return R.ok(roleService.pageRoles(dto));
    }

    /**
     * 根据 ID 查询角色。
     */
    @GetMapping("/{id}")
    public R<RoleVo> getById(@PathVariable Long id) {
        return R.ok(roleService.getById(id));
    }

    /**
     * 新增角色。
     */
    @PostMapping
    public R<RoleVo> save(@Valid @RequestBody RoleSaveDto dto) {
        return R.ok(roleService.saveRole(dto));
    }

    /**
     * 更新角色。
     */
    @PutMapping("/{id}")
    public R<RoleVo> update(@PathVariable Long id, @Valid @RequestBody RoleUpdateDto dto) {
        return R.ok(roleService.updateRole(id, dto));
    }

    /**
     * 删除角色。
     */
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        roleService.removeById(id);
        return R.ok();
    }

    /**
     * 修改角色状态。
     */
    @PatchMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody RoleStatusDto dto) {
        roleService.updateStatus(id, dto.getStatus());
        return R.ok();
    }
}
