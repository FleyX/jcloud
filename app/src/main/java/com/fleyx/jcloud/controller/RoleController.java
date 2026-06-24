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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(CommonConstant.API + "/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping("/page")
    public R<IPage<RoleVo>> page(RolePageQueryDto dto) {
        return R.ok(roleService.pageRoles(dto));
    }

    @GetMapping("/{id}")
    public R<RoleVo> getById(@PathVariable Long id) {
        return R.ok(roleService.getById(id));
    }

    @PostMapping
    public R<RoleVo> save(@Valid @RequestBody RoleSaveDto dto) {
        return R.ok(roleService.saveRole(dto));
    }

    @PutMapping("/{id}")
    public R<RoleVo> update(@PathVariable Long id, @Valid @RequestBody RoleUpdateDto dto) {
        return R.ok(roleService.updateRole(id, dto));
    }

    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        roleService.removeById(id);
        return R.ok();
    }

    @PatchMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody RoleStatusDto dto) {
        roleService.updateStatus(id, dto.getStatus());
        return R.ok();
    }
}
