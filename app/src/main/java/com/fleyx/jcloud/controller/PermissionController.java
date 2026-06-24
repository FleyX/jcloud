package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.model.dto.PermissionSaveDto;
import com.fleyx.jcloud.model.dto.PermissionStatusDto;
import com.fleyx.jcloud.model.dto.PermissionUpdateDto;
import com.fleyx.jcloud.model.po.Resource;
import com.fleyx.jcloud.model.vo.PermissionTreeVo;
import com.fleyx.jcloud.model.vo.PermissionVo;
import com.fleyx.jcloud.service.PermissionService;
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

import java.util.List;

@RestController
@RequestMapping(CommonConstant.API + "/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    @GetMapping("/tree")
    public R<List<PermissionTreeVo>> tree() {
        return R.ok(permissionService.tree());
    }

    @GetMapping("/{id}")
    public R<PermissionVo> getById(@PathVariable Long id) {
        return R.ok(permissionService.getById(id));
    }

    @PostMapping
    public R<PermissionVo> save(@Valid @RequestBody PermissionSaveDto dto) {
        return R.ok(permissionService.savePermission(dto));
    }

    @PutMapping("/{id}")
    public R<PermissionVo> update(@PathVariable Long id, @Valid @RequestBody PermissionUpdateDto dto) {
        return R.ok(permissionService.updatePermission(id, dto));
    }

    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        permissionService.removeById(id);
        return R.ok();
    }

    @PatchMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody PermissionStatusDto dto) {
        permissionService.updateStatus(id, dto.getStatus());
        return R.ok();
    }

    @GetMapping("/resources")
    public R<List<Resource>> listResources() {
        return R.ok(permissionService.listResources());
    }
}
