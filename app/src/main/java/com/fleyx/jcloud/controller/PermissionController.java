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

/**
 * 权限管理控制器。
 */
@RestController
@RequestMapping(CommonConstant.API + "/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    /**
     * 查询权限树。
     */
    @GetMapping("/tree")
    public R<List<PermissionTreeVo>> tree() {
        return R.ok(permissionService.tree());
    }

    /**
     * 根据 ID 查询权限。
     */
    @GetMapping("/{id}")
    public R<PermissionVo> getById(@PathVariable String id) {
        return R.ok(permissionService.getById(id));
    }

    /**
     * 新增权限。
     */
    @PostMapping
    public R<PermissionVo> save(@Valid @RequestBody PermissionSaveDto dto) {
        return R.ok(permissionService.savePermission(dto));
    }

    /**
     * 更新权限。
     */
    @PutMapping("/{id}")
    public R<PermissionVo> update(@PathVariable String id, @Valid @RequestBody PermissionUpdateDto dto) {
        return R.ok(permissionService.updatePermission(id, dto));
    }

    /**
     * 删除权限。
     */
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable String id) {
        permissionService.removeById(id);
        return R.ok();
    }

    /**
     * 修改权限状态。
     */
    @PatchMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable String id, @Valid @RequestBody PermissionStatusDto dto) {
        permissionService.updateStatus(id, dto.getStatus());
        return R.ok();
    }

    /**
     * 查询资源列表。
     */
    @GetMapping("/resources")
    public R<List<Resource>> listResources() {
        return R.ok(permissionService.listResources());
    }
}
